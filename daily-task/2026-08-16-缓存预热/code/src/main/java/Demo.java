import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 这个程序在干嘛？
 * 演示「缓存预热」：系统重启、缓存还是空的时候，怎么在用户上门之前把「最可能被访问的热点数据」
 * 提前灌进 Redis，避免第一波请求把数据库打穿。核心加载动作走真 Jedis 的 Pipeline 批量写。
 *
 * 生活比喻：数据库是早餐店后厨（可靠但慢），Redis 是柜台上的蒸笼（快但会过期）。
 * 平时读数据是「先看蒸笼 → 没有就去后厨拿 → 顺手在蒸笼里放一个」；刚开门时蒸笼是空的，
 * 每个人都要等后厨现做，数据库瞬间被打穿——预热就是「开门前先把最畅销的几笼包子蒸好摆上」。
 *
 * 三个要点（原题给的）：
 *   1) 时机：低峰期触发，而且必须异步（别把预热写在启动主线程里，服务会卡住起不来）
 *   2) 范围：只灌热点，别想全量（10 万商品全灌，内存吃不消，大部分灌了也没人看）
 *   3) 一致性：预热只是提前把复印件摆出来，摆完之后照常「读穿透回填 + 写库删缓存」，每个 key 都要给 TTL
 *
 * 前置条件：本机 6379 上有 Redis（见 docker-compose.yml），无密码。
 * 演示 3 件事：
 *   1) 热点清单怎么来：从历史访问日志数出 Top N（真实项目里这步由埋点 + Flink 算，清单存配置中心）
 *   2) 批量加载：Pipeline 一次发一批 SET，比逐条 SET 少几百倍网络往返
 *   3) TTL 一定要打散：几万个 key 写死同一个 TTL，就会同一秒集体过期，那就是缓存雪崩
 */
public class Demo {

    static final String HOST = "localhost";
    static final int PORT = 6379;
    static final int TOTAL_PRODUCT_COUNT = 100_000;
    static final int ACCESS_LOG_SIZE = 200_000;
    static final int HOT_KEY_TOP_N = 1_000;
    /** 一次 IN 查询别塞太多 key，分批更稳（线上一般 500~1000 一批） */
    static final int BATCH_SIZE = 500;

    public static void main(String[] args) {
        // 造历史访问日志（这就是「埋点数据」）：80% 的请求落在最热的 1000 个商品上，其余散在长尾。
        // 真实项目里这份日志来自埋点，热度清单由 Flink 实时算出来，预热任务启动时先去配置中心拉清单。
        List<Long> accessLog = buildAccessLog(new Random(20260816L), ACCESS_LOG_SIZE);
        // 明天早高峰的真实流量（同一个分布）。验证覆盖率必须用它——不能拿算清单那份日志自己验自己，那样必然虚高。
        List<Long> tomorrowTraffic = buildAccessLog(new Random(20260901L), 20_000);

        try (Jedis jedis = new Jedis(HOST, PORT)) {
            jedis.flushDB();   // 模拟「刚重启，缓存是空的」
            experiment1HotKeyList(accessLog, tomorrowTraffic);
            experiment2PipelineVsOneByOne(jedis, pickTopHotKeys(accessLog, HOT_KEY_TOP_N));
            experiment3SpreadTtl(jedis, pickTopHotKeys(accessLog, HOT_KEY_TOP_N));
            System.out.println("\n【一句话总结】预热 = 只把「历史最热的那一小撮」在低峰期异步灌进缓存，");
            System.out.println("             灌进去之后照常走「读穿透回填 + 写库删缓存」，并且每个 key 都带 TTL。");
        }
    }

    // ==================================================================
    // 实验 1：热点清单怎么算出来（这才是预热真正难的地方）
    // ==================================================================
    private static void experiment1HotKeyList(List<Long> accessLog, List<Long> tomorrowTraffic) {
        printTitle("实验1 热点清单怎么来：从历史访问日志数出 Top N");

        System.out.println("日志里最热的 5 个商品 id：" + pickTopHotKeys(accessLog, 5));
        System.out.println("（不是 1、2、3、4、5，说明这是真从日志里数出来的，不是写死的）");
        System.out.println();
        System.out.println("想预热 | 实际拿到 | 占全部商品 | 盖住明天的流量 | 平均每 key 挡下请求");
        for (int topN : new int[]{100, 500, 1_000, 5_000}) {
            List<Long> hotKeys = pickTopHotKeys(accessLog, topN);
            double coverage = trafficCoverage(tomorrowTraffic, hotKeys);
            double requestsPerKey = coverage / 100.0 * tomorrowTraffic.size() / hotKeys.size();
            System.out.printf("%6d | %8d | %9.1f%% | %13.1f%% | %18.1f%n",
                    topN, hotKeys.size(), hotKeys.size() * 100.0 / TOTAL_PRODUCT_COUNT,
                    coverage, requestsPerKey);
        }
        System.out.println("只灌 1% 的商品（1000 个），就能挡住大部分流量；再往后急剧不划算——");
        System.out.println("key 数从 1000 加到 5000（5 倍），流量只多盖十几个点，每个 key 的性价比掉了一个数量级。");
    }

    // ==================================================================
    // 实验 2：批量加载 vs 逐条加载（预热的核心动作）
    // ==================================================================
    private static void experiment2PipelineVsOneByOne(Jedis jedis, List<Long> hotKeyList) {
        printTitle("实验2 批量加载：Pipeline vs 逐条 SET（同样灌 " + hotKeyList.size() + " 个 key）");

        // 逐条：每个 key 一次网络往返
        long oneByOneStart = System.nanoTime();
        for (Long productId : hotKeyList) {
            jedis.set(cacheKeyOf(productId), valueOf(productId), SetParams.setParams().ex(3600));
        }
        long oneByOneMicros = (System.nanoTime() - oneByOneStart) / 1000;

        jedis.flushDB();

        // Pipeline：把一批 SET 攒起来一次发出去，网络往返只算一次
        long pipelineStart = System.nanoTime();
        Pipeline pipeline = jedis.pipelined();
        for (Long productId : hotKeyList) {
            pipeline.set(cacheKeyOf(productId), valueOf(productId), SetParams.setParams().ex(3600));
        }
        pipeline.sync();
        long pipelineMicros = (System.nanoTime() - pipelineStart) / 1000;

        System.out.printf("逐条 SET          ：%d 个 key / %d 次网络往返，耗时 %d us%n",
                hotKeyList.size(), hotKeyList.size(), oneByOneMicros);
        System.out.printf("Pipeline 批量 SET ：%d 个 key / 1 批命令，耗时 %d us%n",
                hotKeyList.size(), pipelineMicros);
        System.out.printf("快了约 %.1f 倍%n", oneByOneMicros * 1.0 / Math.max(pipelineMicros, 1));
        System.out.println("大白话：去后厨一趟能端 500 个盘子，就别来回跑 1000 趟。");
        System.out.println("但也别一批塞太多（线上 500~1000 一批，对应代码里的 BATCH_SIZE=" + BATCH_SIZE + "），");
        System.out.println("一次攒几万条会把 Redis 的输出缓冲区撑爆。");
    }

    // ==================================================================
    // 实验 3：TTL 一定要打散，否则缓存雪崩
    // ==================================================================
    private static void experiment3SpreadTtl(Jedis jedis, List<Long> hotKeyList) {
        printTitle("实验3 TTL 一定要打散：别让几万个 key 同一秒集体过期");

        jedis.flushDB();
        Random random = new Random(20260816L);
        Pipeline pipeline = jedis.pipelined();
        for (Long productId : hotKeyList) {
            // 3600 秒是基准保质期，再随机抖 0~600 秒，把过期时刻打散
            int ttlSeconds = 3600 + random.nextInt(600);
            pipeline.set(cacheKeyOf(productId), valueOf(productId), SetParams.setParams().ex(ttlSeconds));
        }
        pipeline.sync();

        // 抽查 5 个 key 的剩余 TTL，能看到它们不是一个值
        System.out.print("抽查 5 个 key 的剩余 TTL（秒）：");
        for (Long productId : hotKeyList.subList(0, 5)) {
            System.out.print(jedis.ttl(cacheKeyOf(productId)) + " ");
        }
        System.out.println();
        System.out.println("如果都写死 3600，它们是在同一秒灌进去的，就会在同一秒集体过期——");
        System.out.println("请求瞬间全砸到数据库，这就是缓存雪崩，比不预热还惨。");
        System.out.println("另外记住：Redis 里是复印件，MySQL 才是原件。TTL 到期会自动回源一次，");
        System.out.println("         能自己把「不走删缓存逻辑」留下的脏数据纠正过来。");
    }

    // ==================================================================
    // 热点清单计算（对应「埋点 + Flink 算热度」）
    // ==================================================================

    /** 从访问日志里数出被访问最多的前 topN 个商品 id。 */
    private static List<Long> pickTopHotKeys(List<Long> accessLog, int topN) {
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

    /** 这批热点 key 一共盖住了访问日志里多少比例的流量。 */
    private static double trafficCoverage(List<Long> traffic, List<Long> hotKeys) {
        Set<Long> hotSet = new HashSet<>(hotKeys);
        int covered = 0;
        for (Long productId : traffic) {
            if (hotSet.contains(productId)) {
                covered++;
            }
        }
        return covered * 100.0 / traffic.size();
    }

    /** 造一批访问记录：80% 落在最热的 1000 个商品上，20% 散在长尾（近似电商的二八分布）。 */
    private static List<Long> buildAccessLog(Random random, int size) {
        List<Long> accessLog = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (random.nextDouble() < 0.8) {
                accessLog.add((long) random.nextInt(HOT_KEY_TOP_N) + 1);
            } else {
                accessLog.add((long) random.nextInt(TOTAL_PRODUCT_COUNT) + 1);
            }
        }
        return accessLog;
    }

    private static String cacheKeyOf(long productId) {
        return "product:" + productId;
    }

    /** 演示用的商品快照，真实场景这里是从数据库查出来再序列化进 Redis 的。 */
    private static String valueOf(long productId) {
        return "{\"id\":" + productId + ",\"priceInFen\":" + (1000 + productId % 500) + "}";
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }
}
