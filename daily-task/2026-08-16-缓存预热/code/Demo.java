// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
// ------------------------------------------------------------
// 演示「核心数据的缓存预热怎么做」，严格按原题给的三条思路来：
//   1) 时机：服务启动后异步加载（对应 @PostConstruct / xxl-job 定时触发），不能卡住启动
//   2) 范围：不全量灌，只加载热点 key —— 热点清单靠"历史访问日志统计热度"算出来（对应埋点 + Flink）
//   3) 一致性：预热完不能放任不管，后续照常走"读穿透回填 + 写库删缓存"，并给 key 加 TTL 防僵尸缓存
//   核心加载代码就是原题那两行：
//      List<Data> hotData = dataMapper.selectByHotKeys(hotKeyList);
//      hotData.forEach(d -> redis.set(d.getKey(), d, 3600));
//
// 一共六个实验：
//   实验一：不预热(冷启动) vs 预热后 —— 重启后第一波请求有多少砸到数据库（分段看最真实）
//   实验二：热点清单怎么算 —— 从访问日志统计 Top N，看它能盖住多少流量
//   实验三：批量加载 vs 逐条加载 —— 原题那句 selectByHotKeys 为什么要批量
//   实验四：同步预热 vs 异步预热 —— 启动被卡多久
//   实验五：一致性兜底 —— 写库不删缓存会留下脏数据
//   实验六：TTL 兜底 —— 不设保质期的 key 会变"僵尸缓存"
//
// 说明：这里没有真的 Redis / MySQL，用两个内存小类模拟。
//      数据库的"慢"和 Redis 的"较快"都用一段 CPU 空转按比例模拟
//      （数据库一次往返 ≈ Redis 一次往返的 60 倍，这个量级和线上差不多）。

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class Demo {

    // ================= 被缓存的业务数据 =================
    // record 是 Java 17 支持的写法，可以理解成"只读的小数据袋"
    record Product(long productId, String name, int priceInFen) {
    }

    // ================= 模拟数据库（后厨，慢） =================
    static class FakeDatabase {
        private final Map<Long, Product> rows = new HashMap<>();
        /** 记一下被查了多少次，用来说明"有多少请求真的回源到了数据库" */
        private int singleQueryCount = 0;
        private int batchQueryCount = 0;

        void insert(Product product) {
            rows.put(product.productId(), product);
        }

        /** 单条查询：每次都要走一趟网络 + 磁盘，最贵的就是这个"每次都走一趟" */
        Product selectById(long productId) {
            singleQueryCount++;
            simulateDatabaseCost(1);
            return rows.get(productId);
        }

        /**
         * 批量查询，对应原题的 dataMapper.selectByHotKeys(hotKeyList)。
         * 一次 IN 查询把一批 key 一起捞回来：网络往返只有 1 次，行数的开销才按条算。
         */
        List<Product> selectByHotKeys(List<Long> hotKeys) {
            batchQueryCount++;
            simulateDatabaseCost(hotKeys.size());
            List<Product> result = new ArrayList<>();
            for (Long key : hotKeys) {
                Product product = rows.get(key);
                if (product != null) {
                    result.add(product);
                }
            }
            return result;
        }

        /** 写库：把价格改掉 */
        void updatePrice(long productId, int newPriceInFen) {
            Product old = rows.get(productId);
            rows.put(productId, new Product(productId, old.name(), newPriceInFen));
        }

        int singleQueryCount() {
            return singleQueryCount;
        }

        int batchQueryCount() {
            return batchQueryCount;
        }

        void resetCounters() {
            singleQueryCount = 0;
            batchQueryCount = 0;
        }

        /**
         * 模拟数据库开销：一次网络往返(固定成本) + 每行的读取成本。
         * 固定成本远大于单行成本，这正是"批量查比逐条查划算"的原因——固定成本只付一次。
         */
        private void simulateDatabaseCost(int rowCount) {
            burnCpu(60_000);             // 固定成本：一次网络往返 + 解析 SQL（线上约 0.5~1 ms）
            burnCpu(50 * rowCount);      // 可变成本：每多读一行只加一点点
        }
    }

    // ================= 模拟 Redis（柜台竹屉，快） =================
    // Redis 就是一块放在内存里的"取货柜台"，拿了就走，不用去后厨
    static class FakeRedis {
        /** 一条缓存记录：值 + 过期时间戳。expireAtMillis = 0 表示永不过期 */
        record Entry(Product value, long expireAtMillis) {
        }

        private final Map<String, Entry> store = new ConcurrentHashMap<>();

        /** 带 TTL 的写入。ttlSeconds <= 0 表示不设过期时间（实验六用它演示"僵尸缓存"） */
        void set(String key, Product value, int ttlSeconds) {
            long expireAt = ttlSeconds > 0 ? System.currentTimeMillis() + ttlSeconds * 1000L : 0L;
            store.put(key, new Entry(value, expireAt));
            burnCpu(1_000); // Redis 也要走一趟网络，但比数据库便宜约 60 倍
        }

        /** 读缓存：过期的就当没有（真实 Redis 也是这样惰性删除的） */
        Product get(String key) {
            burnCpu(1_000);
            Entry entry = store.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expireAtMillis() > 0 && System.currentTimeMillis() > entry.expireAtMillis()) {
                store.remove(key);
                return null;
            }
            return entry.value();
        }

        /** 删缓存：写完数据库要做的事 */
        void delete(String key) {
            store.remove(key);
        }

        int size() {
            return store.size();
        }
    }

    // ================= 业务读写：标准的"缓存旁路"套路 =================
    // 缓存旁路(cache-aside)说白了就是：先看竹屉里有没有，没有才去后厨拿，拿回来顺手放一个到竹屉
    static class ProductService {
        private final FakeRedis redis;
        private final FakeDatabase database;
        private int cacheHitCount = 0;
        private int cacheMissCount = 0;

        ProductService(FakeRedis redis, FakeDatabase database) {
            this.redis = redis;
            this.database = database;
        }

        /** 读：命中就直接返回；没命中就回源数据库，并把结果放回缓存（这一步叫"回填"） */
        Product getProduct(long productId) {
            String key = cacheKeyOf(productId);
            Product cached = redis.get(key);
            if (cached != null) {
                cacheHitCount++;
                return cached;
            }
            cacheMissCount++;
            Product fromDatabase = database.selectById(productId);
            if (fromDatabase != null) {
                redis.set(key, fromDatabase, 3600);
            }
            return fromDatabase;
        }

        /**
         * 写：先更新数据库，再把缓存删掉。
         * 顺序很关键——删了缓存，下一个来读的人自然会去数据库拿到新值再回填。
         */
        void updatePriceCorrectly(long productId, int newPriceInFen) {
            database.updatePrice(productId, newPriceInFen);
            redis.delete(cacheKeyOf(productId));
        }

        /** 反面教材：只改数据库，忘了删缓存 —— 缓存里就一直是旧价格 */
        void updatePriceWrongly(long productId, int newPriceInFen) {
            database.updatePrice(productId, newPriceInFen);
        }

        int cacheHitCount() {
            return cacheHitCount;
        }

        int cacheMissCount() {
            return cacheMissCount;
        }

        static String cacheKeyOf(long productId) {
            return "product:" + productId;
        }
    }

    // ================= 热点清单计算（对应"埋点 + Flink 算热度"） =================
    static class HotKeyAnalyzer {
        /**
         * 从历史访问日志里数一数每个商品被访问了几次，取访问最多的前 topN 个。
         * 真实项目里这一步跑在 Flink / 离线任务里，算完把清单丢到配置中心，
         * 预热任务启动时先去拉最新清单；这里就是个"数正字"的过程，逻辑一样。
         */
        static List<Long> pickTopHotKeys(List<Long> accessLog, int topN) {
            Map<Long, Integer> visitCountOfProduct = new HashMap<>();
            for (Long productId : accessLog) {
                visitCountOfProduct.merge(productId, 1, Integer::sum);
            }
            List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(visitCountOfProduct.entrySet());
            sorted.sort(Comparator.<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed());

            List<Long> hotKeys = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
                hotKeys.add(sorted.get(i).getKey());
            }
            return hotKeys;
        }

        /** 这批热点 key 一共盖住了访问日志里多少比例的流量 */
        static double trafficCoverage(List<Long> accessLog, List<Long> hotKeys) {
            Set<Long> hotSet = new HashSet<>(hotKeys);
            int covered = 0;
            for (Long productId : accessLog) {
                if (hotSet.contains(productId)) {
                    covered++;
                }
            }
            return covered * 100.0 / accessLog.size();
        }
    }

    // ================= 预热器（对应 @PostConstruct / xxl-job 触发的那个任务） =================
    static class CacheWarmer {
        /** IN 查询一次别塞太多 key，分批更稳（线上一般 500~1000 一批） */
        private static final int BATCH_SIZE = 500;

        private final FakeRedis redis;
        private final FakeDatabase database;

        CacheWarmer(FakeRedis redis, FakeDatabase database) {
            this.redis = redis;
            this.database = database;
        }

        /**
         * 原题的核心两行就在这里（外面套了个分批循环）：
         *   List<Data> hotData = dataMapper.selectByHotKeys(hotKeyList);
         *   hotData.forEach(d -> redis.set(d.getKey(), d, 3600));
         * 注意那个 3600 —— TTL 一定要给，理由见实验六。
         */
        int warmUp(List<Long> hotKeyList, int ttlSeconds) {
            int loadedCount = 0;
            for (int from = 0; from < hotKeyList.size(); from += BATCH_SIZE) {
                int to = Math.min(from + BATCH_SIZE, hotKeyList.size());
                List<Product> hotData = database.selectByHotKeys(hotKeyList.subList(from, to));
                for (Product product : hotData) {
                    redis.set(ProductService.cacheKeyOf(product.productId()), product, ttlSeconds);
                    loadedCount++;
                }
            }
            return loadedCount;
        }

        /** 逐条加载版本：反面教材，用来对比批量的好处 */
        int warmUpOneByOne(List<Long> hotKeyList, int ttlSeconds) {
            int loadedCount = 0;
            for (Long productId : hotKeyList) {
                Product product = database.selectById(productId);
                if (product != null) {
                    redis.set(ProductService.cacheKeyOf(productId), product, ttlSeconds);
                    loadedCount++;
                }
            }
            return loadedCount;
        }
    }

    // ================= 访问分布：Zipf（真实电商最接近的那种"爆款吃掉大半流量"） =================
    /**
     * Zipf 分布：排名第 1 热的商品被访问的概率 ∝ 1/1^s，第 2 热 ∝ 1/2^s ……
     * 说白了就是"畅销榜前几名卖掉大半的货，长尾商品几乎没人看"。
     * 为了证明热点清单真是从日志里数出来的（而不是写死的 1、2、3），
     * 这里额外把"热度排名"随机映射到"商品 id"，所以最热的商品 id 是个乱数。
     */
    static class ZipfSampler {
        private final double[] cumulativeProbabilityByRank;
        private final long[] productIdByRank;
        private final Random random;

        ZipfSampler(int itemCount, double skew, Random random) {
            this.random = random;

            // 第一步：算出每个排名的累积概率（CDF），后面用二分查找来抽样
            cumulativeProbabilityByRank = new double[itemCount];
            double runningSum = 0;
            for (int rank = 1; rank <= itemCount; rank++) {
                runningSum += 1.0 / Math.pow(rank, skew);
                cumulativeProbabilityByRank[rank - 1] = runningSum;
            }
            for (int i = 0; i < itemCount; i++) {
                cumulativeProbabilityByRank[i] /= runningSum;
            }

            // 第二步：把排名打乱映射到商品 id，让"最热的商品"不是 1 号
            productIdByRank = new long[itemCount];
            for (int i = 0; i < itemCount; i++) {
                productIdByRank[i] = i + 1;
            }
            Random shuffleRandom = new Random(20260816L);
            for (int i = itemCount - 1; i > 0; i--) {
                int j = shuffleRandom.nextInt(i + 1);
                long tmp = productIdByRank[i];
                productIdByRank[i] = productIdByRank[j];
                productIdByRank[j] = tmp;
            }
        }

        long nextProductId() {
            double dice = random.nextDouble();
            int rankIndex = Arrays.binarySearch(cumulativeProbabilityByRank, dice);
            if (rankIndex < 0) {
                rankIndex = -rankIndex - 1;
            }
            if (rankIndex >= productIdByRank.length) {
                rankIndex = productIdByRank.length - 1;
            }
            return productIdByRank[rankIndex];
        }
    }

    // ================= 参数 =================
    private static final int TOTAL_PRODUCT_COUNT = 100_000;
    private static final int ACCESS_LOG_SIZE = 200_000;
    private static final int MORNING_PEAK_REQUEST_COUNT = 20_000;
    private static final int SEGMENT_COUNT = 10;
    private static final int HOT_KEY_TOP_N = 1_000;
    private static final int BIG_HOT_KEY_TOP_N = 20_000;

    public static void main(String[] args) throws Exception {
        FakeDatabase database = new FakeDatabase();
        for (long productId = 1; productId <= TOTAL_PRODUCT_COUNT; productId++) {
            database.insert(new Product(productId, "商品-" + productId, 1000 + (int) (productId % 500)));
        }

        // 造历史访问日志（这就是"埋点数据"），以及紧接着的早高峰真实请求。
        // 两者同一个分布——历史能预测未来，预热才有意义。
        ZipfSampler sampler = new ZipfSampler(TOTAL_PRODUCT_COUNT, 1.2, new Random(20260816L));
        List<Long> accessLog = new ArrayList<>(ACCESS_LOG_SIZE);
        for (int i = 0; i < ACCESS_LOG_SIZE; i++) {
            accessLog.add(sampler.nextProductId());
        }
        List<Long> morningPeakRequests = new ArrayList<>(MORNING_PEAK_REQUEST_COUNT);
        for (int i = 0; i < MORNING_PEAK_REQUEST_COUNT; i++) {
            morningPeakRequests.add(sampler.nextProductId());
        }

        System.out.println("商品总数 = " + TOTAL_PRODUCT_COUNT
                + "，历史访问日志 = " + ACCESS_LOG_SIZE + " 条"
                + "，重启后早高峰请求 = " + MORNING_PEAK_REQUEST_COUNT + " 条");

        List<Long> hotKeyList = HotKeyAnalyzer.pickTopHotKeys(accessLog, HOT_KEY_TOP_N);
        List<Long> bigHotKeyList = HotKeyAnalyzer.pickTopHotKeys(accessLog, BIG_HOT_KEY_TOP_N);

        experiment1ColdStartVersusWarmedUp(database, hotKeyList, morningPeakRequests);
        experiment2HowToFindHotKeys(accessLog, morningPeakRequests);
        experiment3BatchVersusOneByOne(database, hotKeyList);
        experiment4SyncVersusAsyncWarmUp(database, bigHotKeyList);
        experiment5ConsistencyFallback(database);
        experiment6TtlAgainstZombieCache(database);

        System.out.println();
        System.out.println("【一句话总结】预热 = 只把「历史最热的那一小撮」在低峰期异步灌进缓存，");
        System.out.println("             灌进去之后照常走「读穿透回填 + 写库删缓存」，并且每个 key 都带 TTL。");
    }

    // ---------------------------------------------------------------
    // 实验一：不预热 vs 预热后，重启后第一波请求有多少砸到数据库
    // 关键：分段看！预热保护的就是"最开始那几秒"，看总数会被后面的自然回填稀释掉。
    // ---------------------------------------------------------------
    private static void experiment1ColdStartVersusWarmedUp(
            FakeDatabase database, List<Long> hotKeyList, List<Long> morningPeakRequests) {
        printTitle("实验一：不预热(冷启动) vs 预热后，重启后有多少请求砸到数据库");

        // ---- 场景 A：完全不预热，缓存是空的，客人一来全冲后厨 ----
        FakeRedis coldRedis = new FakeRedis();
        ProductService coldService = new ProductService(coldRedis, database);
        database.resetCounters();
        long coldStartNanos = System.nanoTime();
        int[] coldMissPerSegment = replayRequestsBySegment(coldService, morningPeakRequests);
        long coldCostNanos = System.nanoTime() - coldStartNanos;

        // ---- 场景 B：先预热 Top N 热点，再迎客 ----
        FakeRedis warmRedis = new FakeRedis();
        ProductService warmService = new ProductService(warmRedis, database);
        int loadedCount = new CacheWarmer(warmRedis, database).warmUp(hotKeyList, 3600);
        database.resetCounters();
        long warmStartNanos = System.nanoTime();
        int[] warmMissPerSegment = replayRequestsBySegment(warmService, morningPeakRequests);
        long warmCostNanos = System.nanoTime() - warmStartNanos;

        int segmentSize = MORNING_PEAK_REQUEST_COUNT / SEGMENT_COUNT;
        System.out.println("  （每段 " + segmentSize + " 条请求，数字是这一段里真正回源数据库的次数）");
        System.out.println("     第几段 | 不预热 | 预热后 | 少了多少");
        for (int seg = 0; seg < SEGMENT_COUNT; seg++) {
            System.out.printf("  %9d | %6d | %6d | %6.1f%%%n",
                    seg + 1, coldMissPerSegment[seg], warmMissPerSegment[seg],
                    (coldMissPerSegment[seg] - warmMissPerSegment[seg]) * 100.0 / coldMissPerSegment[seg]);
        }

        System.out.printf("  合计回源：不预热 %d 次 / 预热后 %d 次；总耗时 %s vs %s%n",
                coldService.cacheMissCount(), warmService.cacheMissCount(),
                formatDuration(coldCostNanos), formatDuration(warmCostNanos));
        System.out.printf("  命中率：不预热 %.1f%% / 预热后 %.1f%%%n",
                hitRate(coldService), hitRate(warmService));
        System.out.printf("  结论：只灌了 %d 个 key（占全部商品的 %.1f%%），第一段回源就从 %d 次压到 %d 次。%n",
                loadedCount, loadedCount * 100.0 / TOTAL_PRODUCT_COUNT,
                coldMissPerSegment[0], warmMissPerSegment[0]);
        System.out.println("  注意看表格：不预热的话第一段回源最凶（数据库就是这时候被打穿的），");
        System.out.println("            越往后自己回填得越多、回源自然变少 —— 所以只看总数会低估预热的价值。");
        System.out.println("  大白话：开门前先蒸好最畅销的那几笼包子，第一批客人不用排队等和面。");
    }

    /** 按段回放请求，返回每段真正回源数据库的次数 */
    private static int[] replayRequestsBySegment(ProductService service, List<Long> requests) {
        int segmentSize = requests.size() / SEGMENT_COUNT;
        int[] missPerSegment = new int[SEGMENT_COUNT];
        for (int seg = 0; seg < SEGMENT_COUNT; seg++) {
            int missBefore = service.cacheMissCount();
            int from = seg * segmentSize;
            int to = (seg == SEGMENT_COUNT - 1) ? requests.size() : from + segmentSize;
            for (int i = from; i < to; i++) {
                service.getProduct(requests.get(i));
            }
            missPerSegment[seg] = service.cacheMissCount() - missBefore;
        }
        return missPerSegment;
    }

    // ---------------------------------------------------------------
    // 实验二：热点清单怎么算出来（这才是预热真正难的地方）
    // ---------------------------------------------------------------
    private static void experiment2HowToFindHotKeys(List<Long> accessLog, List<Long> futureRequests) {
        printTitle("实验二：热点清单怎么算 —— 从历史访问日志数出 Top N");

        List<Long> top5 = HotKeyAnalyzer.pickTopHotKeys(accessLog, 5);
        System.out.println("  日志里最热的 5 个商品 id：" + top5);
        System.out.println("  （不是 1、2、3、4、5，说明这是真从日志里数出来的，不是写死的）");
        System.out.println();
        System.out.println("  重点：清单是用「昨天的日志」算的，覆盖率必须拿「今天的真实流量」来验，");
        System.out.println("       不能拿算清单那份日志自己验自己，那样必然虚高。");
        System.out.println();

        int[] topNCandidates = {100, 500, 1_000, 5_000, 20_000};
        System.out.println("  想预热 | 实际拿到 | 占全部商品 | 盖住明天的流量 | 平均每 key 挡下请求");
        for (int topN : topNCandidates) {
            List<Long> hotKeys = HotKeyAnalyzer.pickTopHotKeys(accessLog, topN);
            double coverage = HotKeyAnalyzer.trafficCoverage(futureRequests, hotKeys);
            double requestsPerKey = coverage / 100.0 * futureRequests.size() / hotKeys.size();
            System.out.printf("  %6d | %8d | %9.1f%% | %13.1f%% | %18.1f%n",
                    topN, hotKeys.size(), hotKeys.size() * 100.0 / TOTAL_PRODUCT_COUNT,
                    coverage, requestsPerKey);
        }
        System.out.println("  （最后一行「实际拿到」不足 20000，是因为日志里出现过的商品本来就没那么多）");
        System.out.println("  结论：越往后越不划算 —— key 数从 1000 加到 1.8 万（18 倍），");
        System.out.println("       盖住的流量只多了十几个点，但每个 key 的性价比掉了一个数量级。");
        System.out.println("  大白话：畅销榜前几名卖掉了大半的货，冷门商品灌进缓存基本没人看，纯占内存。");
    }

    // ---------------------------------------------------------------
    // 实验三：批量加载 vs 逐条加载（原题那句 selectByHotKeys 的意义）
    // ---------------------------------------------------------------
    private static void experiment3BatchVersusOneByOne(FakeDatabase database, List<Long> hotKeyList) {
        printTitle("实验三：批量加载 vs 逐条加载，同样灌 " + hotKeyList.size() + " 个 key");

        FakeRedis redisForOneByOne = new FakeRedis();
        database.resetCounters();
        long oneByOneStart = System.nanoTime();
        new CacheWarmer(redisForOneByOne, database).warmUpOneByOne(hotKeyList, 3600);
        long oneByOneNanos = System.nanoTime() - oneByOneStart;
        int oneByOneQueries = database.singleQueryCount();

        FakeRedis redisForBatch = new FakeRedis();
        database.resetCounters();
        long batchStart = System.nanoTime();
        new CacheWarmer(redisForBatch, database).warmUp(hotKeyList, 3600);
        long batchNanos = System.nanoTime() - batchStart;
        int batchQueries = database.batchQueryCount();

        System.out.printf("  逐条 selectById    ：查数据库 %4d 次，耗时 %s%n",
                oneByOneQueries, formatDuration(oneByOneNanos));
        System.out.printf("  批量 selectByHotKeys：查数据库 %4d 次，耗时 %s（每批 500 个 key）%n",
                batchQueries, formatDuration(batchNanos));
        System.out.printf("  灌进缓存的数量一样：%d vs %d，但快了约 %.0f 倍%n",
                redisForOneByOne.size(), redisForBatch.size(), oneByOneNanos * 1.0 / batchNanos);
        System.out.println("  大白话：去后厨一趟能端 500 个盘子，就别来回跑 1000 趟。");
        System.out.println("  实践提醒：IN 里的 key 别一次塞太多（500~1000 一批），太大会让 SQL 变慢甚至打爆内存。");
    }

    // ---------------------------------------------------------------
    // 实验四：同步预热 vs 异步预热 —— 启动会被卡多久
    // ---------------------------------------------------------------
    private static void experiment4SyncVersusAsyncWarmUp(FakeDatabase database, List<Long> bigHotKeyList)
            throws Exception {
        printTitle("实验四：同步预热 vs 异步预热（这次灌 " + bigHotKeyList.size() + " 个 key，模拟大清单）");

        // 同步版：在 @PostConstruct 里直接 warmUp()，加载没跑完服务就起不来
        FakeRedis syncRedis = new FakeRedis();
        long syncStart = System.nanoTime();
        new CacheWarmer(syncRedis, database).warmUp(bigHotKeyList, 3600);
        long syncBlockedNanos = System.nanoTime() - syncStart;
        System.out.printf("  同步预热：启动线程被阻塞 %s，这期间服务起不来、探活失败（缓存已灌 %d 个）%n",
                formatDuration(syncBlockedNanos), syncRedis.size());

        // 异步版：@PostConstruct 里只丢一个后台线程，主线程立刻放行
        FakeRedis asyncRedis = new FakeRedis();
        CountDownLatch warmUpFinished = new CountDownLatch(1);
        long asyncStart = System.nanoTime();
        Thread warmUpThread = new Thread(() -> {
            new CacheWarmer(asyncRedis, database).warmUp(bigHotKeyList, 3600);
            warmUpFinished.countDown();
        }, "cache-warm-up-thread");
        warmUpThread.start();
        long asyncBlockedNanos = System.nanoTime() - asyncStart;
        System.out.printf("  异步预热：启动线程只被阻塞 %s，服务立刻能接流量%n", formatDuration(asyncBlockedNanos));

        warmUpFinished.await();
        System.out.println("  后台线程干完活后，缓存里也是 " + asyncRedis.size() + " 个 key");
        System.out.println("  换算一下：这里是内存模拟才这么快。线上一次 MySQL 往返约 1 ms、Redis 约 0.2 ms，");
        System.out.printf("           灌 %d 个 key ≈ %d 次批量查 + %d 次 set ≈ 几十秒，同步做就是几十秒起不来。%n",
                bigHotKeyList.size(), bigHotKeyList.size() / 500, bigHotKeyList.size());
        System.out.println("  大白话：开门时间到就先开门，包子在后厨接着蒸，别让客人在门外干等。");
        System.out.println("  代价：预热完成前进来的请求还是会回源数据库，所以这活儿要放在低峰期做。");
    }

    // ---------------------------------------------------------------
    // 实验五：一致性兜底 —— 写库不删缓存，预热进去的数据就变脏数据
    // ---------------------------------------------------------------
    private static void experiment5ConsistencyFallback(FakeDatabase database) {
        printTitle("实验五：一致性兜底 —— 预热完不能放任不管");

        long productId = 7L;
        int originalPrice = database.selectById(productId).priceInFen();

        // ---- 反面教材：只改数据库，忘了删缓存 ----
        FakeRedis wrongRedis = new FakeRedis();
        ProductService wrongService = new ProductService(wrongRedis, database);
        new CacheWarmer(wrongRedis, database).warmUp(List.of(productId), 3600);
        int priceReadBeforeWrongUpdate = wrongService.getProduct(productId).priceInFen();
        wrongService.updatePriceWrongly(productId, 99);
        int priceReadAfterWrongUpdate = wrongService.getProduct(productId).priceInFen();
        System.out.printf("  只改库、不删缓存：改价前读到 %d 分；运营把价格改成 99 分后，用户依然读到 %d 分  <-- 脏数据%n",
                priceReadBeforeWrongUpdate, priceReadAfterWrongUpdate);

        // ---- 正确做法：更新数据库 + 删缓存，下一个来读的人自然回填新值 ----
        database.updatePrice(productId, originalPrice); // 价格改回去，方便公平对比
        FakeRedis rightRedis = new FakeRedis();
        ProductService rightService = new ProductService(rightRedis, database);
        new CacheWarmer(rightRedis, database).warmUp(List.of(productId), 3600);
        int priceReadBeforeRightUpdate = rightService.getProduct(productId).priceInFen();
        rightService.updatePriceCorrectly(productId, 99);
        int priceReadAfterRightUpdate = rightService.getProduct(productId).priceInFen();
        System.out.printf("  更新库 + 删缓存  ：改价前读到 %d 分；改成 99 分后立刻读到 %d 分  <-- 正确%n",
                priceReadBeforeRightUpdate, priceReadAfterRightUpdate);

        System.out.println("  大白话：柜台价签是后厨价格的复印件。后厨改价就得把旧价签撕掉，");
        System.out.println("        下一个客人来的时候自然会拿到新的那张。");
    }

    // ---------------------------------------------------------------
    // 实验六：TTL 兜底 —— 不设保质期的 key 会变僵尸缓存
    // ---------------------------------------------------------------
    private static void experiment6TtlAgainstZombieCache(FakeDatabase database) throws Exception {
        printTitle("实验六：TTL 兜底 —— 不设过期时间会留下「僵尸缓存」");

        long productId = 8L;
        int originalPrice = database.selectById(productId).priceInFen();

        // ---- 反面教材：预热时 ttl 传 0，等于永不过期 ----
        FakeRedis noTtlRedis = new FakeRedis();
        ProductService noTtlService = new ProductService(noTtlRedis, database);
        new CacheWarmer(noTtlRedis, database).warmUp(List.of(productId), 0);
        // 数据库被别的途径改了（比如运维直接改库、别的老系统写库没走我们的删缓存逻辑）
        database.updatePrice(productId, 555);
        Thread.sleep(1_100);
        System.out.printf("  不设 TTL   ：数据库已经是 555 分，1.1 秒后缓存还是 %d 分，而且会一直错下去  <-- 僵尸缓存%n",
                noTtlService.getProduct(productId).priceInFen());

        // ---- 正确做法：给 TTL，到期后自动重新回源 ----
        database.updatePrice(productId, originalPrice);
        FakeRedis ttlRedis = new FakeRedis();
        ProductService ttlService = new ProductService(ttlRedis, database);
        new CacheWarmer(ttlRedis, database).warmUp(List.of(productId), 1); // 演示用 1 秒，线上一般 3600 秒
        System.out.printf("  设 1 秒 TTL：刚预热完读到 %d 分%n", ttlService.getProduct(productId).priceInFen());
        database.updatePrice(productId, 555);
        Thread.sleep(1_100);
        System.out.printf("  TTL 到期再读：缓存已失效，自动回源数据库读到 %d 分  <-- 自己纠错了%n",
                ttlService.getProduct(productId).priceInFen());

        System.out.println("  大白话：包子贴保质期标签，过期自动下架重蒸；不贴标签那笼放到发霉也没人管。");
        System.out.println("  重要提醒：同一批预热的 key，TTL 一定要打散（比如 3600 + 随机 0~600 秒），");
        System.out.println("          否则几万个 key 同一秒集体过期，请求全砸数据库 —— 那叫缓存雪崩。");
    }

    // ================= 小工具 =================

    private static double hitRate(ProductService service) {
        int total = service.cacheHitCount() + service.cacheMissCount();
        return total == 0 ? 0 : service.cacheHitCount() * 100.0 / total;
    }

    /** 耗时格式化：小的用微秒，大的用毫秒，免得都打印成 0 ms 看不出差别 */
    private static String formatDuration(long nanos) {
        double micros = nanos / 1_000.0;
        if (micros < 1_000) {
            return String.format("%.0f us", micros);
        }
        return String.format("%.1f ms", micros / 1_000.0);
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }

    /** 用一段 CPU 空转模拟"这一步要花时间"，rounds 越大越慢。攒住结果防止 JIT 把它整段优化掉。 */
    private static long cpuBurnSink = 0;

    private static void burnCpu(int rounds) {
        long accumulator = 0;
        for (int i = 1; i <= rounds; i++) {
            accumulator += (i * 31L) % 97;
        }
        cpuBurnSink += accumulator;
    }
}
