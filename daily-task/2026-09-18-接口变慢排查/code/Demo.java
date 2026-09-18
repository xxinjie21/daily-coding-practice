// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 题目：接口变慢了应该如何排查？导致接口变慢的原因有哪些？
//
// 接口变慢不是「一个问题」，而是「系统某一站亮了红灯」。排查的核心是分层测量，
// 不是靠猜。下面用 6 个小实验，把排查思路和四类原因一个个演出来：
//
//   实验 1  链路耗时拆分 —— 手写迷你版 SkyWalking，量出每一站花了多久、谁占大头
//   实验 2  慢 SQL      —— 加索引前后，摸的行数和耗时的差距（应用代码/数据库类）
//   实验 3  外部依赖     —— 不设超时，一个慢第三方怎样把整个应用的线程池耗干
//   实验 4  缓存击穿     —— Redis 挂了请求全砸数据库，本地缓存怎么兜底
//   实验 5  死锁        —— 应用代码类原因的极端情况，以及怎么用程序自动发现它
//   实验 6  平均 vs P99  —— 为什么平均耗时很健康，用户还是在喊慢
//
// 全部用 JDK 自带类，不需要任何第三方 jar，单文件直接编译运行。
// ============================================================================

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    /** 一张 100 万行的假订单表，实验 1 和实验 2 都用它。 */
    private static final FakeDatabase DATABASE = new FakeDatabase(1_000_000);

    /** 假想中的本地缓存，实验 1 里拿来当「Redis 命中」那一站。 */
    private static final Map<String, String> LOCAL_CACHE = new HashMap<>();

    /** 用来接住 burnCpu 的结果，防止 JIT 把空循环当废代码优化掉。 */
    private static volatile long cpuSink = 0;

    public static void main(String[] args) throws Exception {
        LOCAL_CACHE.put("hot:product:1", "缓存里的商品");

        warmUp();                       // 先热身，让 JVM 把热点代码编译好，后面的耗时才准
        experiment1_trace();
        experiment2_slowSql();
        experiment3_noTimeout();
        experiment4_cacheBreakdown();
        experiment5_deadlock();
        experiment6_avgVsP99();

        System.out.println();
        System.out.println("========== 全部实验结束 ==========");
    }

    // ========================================================================
    // 实验 1：链路耗时拆分
    // ========================================================================
    private static void experiment1_trace() throws Exception {
        System.out.println("========== 1. 链路耗时拆分：先量出每一站花了多久 ==========");
        System.out.println("(真实项目里这一步由 SkyWalking / Prometheus 自动完成，这里手写一个迷你版)");
        System.out.println();

        List<Span> spans = new ArrayList<>();

        long start = System.nanoTime();
        burnCpu(100_000);                       // 网关鉴权 + 本地业务逻辑（这块很轻）
        spans.add(new Span("网关鉴权 + 本地计算", elapsedMillis(start)));

        start = System.nanoTime();
        LOCAL_CACHE.get("hot:product:1");       // 查 Redis 缓存
        spans.add(new Span("Redis 查缓存", elapsedMillis(start)));

        start = System.nanoTime();
        // 这个接口查了两次库：一次查列表、一次查总数，两次都没索引，只能全表翻
        DATABASE.queryByScan(7);
        DATABASE.queryByScan(7);
        spans.add(new Span("MySQL 查询(没索引，查了2次)", elapsedMillis(start)));

        start = System.nanoTime();
        sleepQuietly(1);                        // 调第三方接口，对方 1 毫秒就回了（它没问题）
        spans.add(new Span("调用第三方 HTTP", elapsedMillis(start)));

        double total = spans.stream().mapToDouble(Span::costMillis).sum();
        for (Span span : spans) {
            System.out.printf("   %s : 耗时 %.2f ms , 占比 %.1f%%%n",
                    span.name(), span.costMillis(), span.costMillis() / total * 100);
        }

        Span slowest = spans.stream().max(Comparator.comparingDouble(Span::costMillis)).orElseThrow();
        System.out.println();
        System.out.printf("   总耗时 %.2f ms ，最大头是「%s」，占 %.1f%% -> 就先查它%n",
                total, slowest.name(), slowest.costMillis() / total * 100);
        System.out.println("   提醒：别一上来就翻代码。先量出谁占大头，再往下钻。");
    }

    // ========================================================================
    // 实验 2：慢 SQL
    // ========================================================================
    private static void experiment2_slowSql() {
        System.out.println();
        System.out.println("========== 2. 慢 SQL：加索引前后差多少 ==========");
        System.out.println("(这是「应用代码 / 数据库」类原因里最常见的一种)");
        System.out.println();

        long start = System.nanoTime();
        List<OrderRow> byScan = DATABASE.queryByScan(7);
        double scanMs = elapsedMillis(start);
        long scanRows = DATABASE.scannedRows;

        start = System.nanoTime();
        List<OrderRow> byIndex = DATABASE.queryByIndex(7);
        double indexMs = elapsedMillis(start);
        long indexRows = DATABASE.scannedRows;

        System.out.printf("   全表扫描 : 摸了 %d 行 , 耗时 %.2f ms , 命中 %d 条%n", scanRows, scanMs, byScan.size());
        System.out.printf("   走索引   : 摸了 %d 行 , 耗时 %.3f ms , 命中 %d 条%n", indexRows, indexMs, byIndex.size());
        System.out.printf("   结论 : 少摸 %.0f 倍的行，快了约 %.0f 倍，两边结果一致吗 -> %s%n",
                scanRows * 1.0 / Math.max(indexRows, 1),
                scanMs / Math.max(indexMs, 0.001),
                byScan.equals(byIndex) ? "一致" : "不一致");
        System.out.println("   对应实验 1：如果数据库那一格占了大头，先去看 EXPLAIN 有没有走索引。");
    }

    // ========================================================================
    // 实验 3：外部依赖不设超时
    // ========================================================================
    private static void experiment3_noTimeout() throws Exception {
        System.out.println();
        System.out.println("========== 3. 外部依赖不设超时：一个慢接口拖死整个应用 ==========");
        System.out.println("(把 Tomcat 的 200 个线程缩小成 4 个，方便看清线程池是怎么被占满的)");
        System.out.println();

        int thirdPartyCostMs = 1200;    // 第三方抖动，1.2 秒才回

        // ---- 场景 A：不设超时，线程只能陪着干等 ----
        ThreadPoolExecutor poolA = newPool();
        occupyAllThreads(poolA, () -> callThirdParty(thirdPartyCostMs, Integer.MAX_VALUE));
        long waitA = measureLocalTask(poolA);
        System.out.printf("   [A] 不设超时      : 一个跟第三方毫无关系的本地接口，等了 %d ms 才轮到线程%n", waitA);
        poolA.shutdownNow();

        // ---- 场景 B：设了 200ms 超时，到点就走人 ----
        ThreadPoolExecutor poolB = newPool();
        occupyAllThreads(poolB, () -> callThirdParty(thirdPartyCostMs, 200));
        long waitB = measureLocalTask(poolB);
        System.out.printf("   [B] 设 200ms 超时 : 同一个本地接口，只等了 %d ms%n", waitB);
        poolB.shutdownNow();

        System.out.printf("   结论 : 配好超时后，本地接口少等 %.1f 倍。%n", waitA * 1.0 / Math.max(waitB, 1));
        System.out.println("          Feign / RestTemplate 默认是不设超时的，对方 30 秒不回你就等 30 秒，");
        System.out.println("          几十个请求一来线程全被占满，连不相关的接口也一起堵死 —— 这是最容易被漏掉的一条。");
    }

    /** 4 个线程的线程池，模拟缩小版的 Tomcat。 */
    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    /** 丢 4 个「调第三方」的任务进去，把 4 个线程全占住。 */
    private static void occupyAllThreads(ThreadPoolExecutor pool, Runnable task) throws Exception {
        for (int i = 0; i < 4; i++) {
            pool.execute(task);
        }
        Thread.sleep(60);   // 等它们真的进到「等第三方」的状态
    }

    /** 提交一个纯本地任务，测它要等多久才拿到线程。 */
    private static long measureLocalTask(ThreadPoolExecutor pool) throws Exception {
        long start = System.currentTimeMillis();
        pool.submit(() -> "本地接口返回").get();
        return System.currentTimeMillis() - start;
    }

    /**
     * 模拟一次 HTTP 调用：对方要 costMs 之后才回。
     * timeoutMs 是你配的超时时间 —— 到点没回就放弃，绝不陪着等。
     */
    private static void callThirdParty(int costMs, int timeoutMs) {
        int waitMs = Math.min(costMs, timeoutMs);   // 设了超时，最多等 timeoutMs
        sleepQuietly(waitMs);
    }

    // ========================================================================
    // 实验 4：缓存击穿
    // ========================================================================
    private static void experiment4_cacheBreakdown() throws Exception {
        System.out.println();
        System.out.println("========== 4. 缓存击穿：Redis 一挂，请求全砸到数据库 ==========");
        System.out.println();

        String hotKey = "hot:product:1";
        int threadCount = 100;

        // ---- 场景 A：Redis 挂了，谁都不兜底，100 个请求全去查库 ----
        FakeRedis redis = new FakeRedis();
        redis.down = true;                 // Redis 挂了
        DATABASE.resetCounters();
        runConcurrent(threadCount, () -> {
            String value = redis.get(hotKey);
            if (value == null) {
                DATABASE.queryHotProduct();     // 没缓存，只好现查数据库
            }
        });
        long dbHitsA = DATABASE.queryCount.get();
        System.out.printf("   [A] 没有兜底 : 数据库被打了 %d 次%n", dbHitsA);

        // ---- 场景 B：加一层本地缓存兜底 ----
        redis.down = true;
        DATABASE.resetCounters();
        LocalCacheGuard guard = new LocalCacheGuard(redis, DATABASE);
        runConcurrent(threadCount, () -> guard.get(hotKey));
        long dbHitsB = DATABASE.queryCount.get();
        System.out.printf("   [B] 本地缓存兜底 : 数据库只被打了 %d 次%n", dbHitsB);

        System.out.printf("   结论 : 数据库压力从 %d 次降到 %d 次，100 个用户拿到的还是同一份数据。%n", dbHitsA, dbHitsB);
        System.out.println("          MySQL 扛不住这种突发读流量，所以 Redis 挂了必须有本地缓存顶一下。");
    }

    /** 让 threadCount 个线程「同时」执行 task —— 就像热点 key 过期的同一瞬间涌进来一堆请求。 */
    private static void runConcurrent(int threadCount, Runnable task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threadCount);   // 发令枪：大家都到位后一起冲
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    ready.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        done.await();
    }

    // ========================================================================
    // 实验 5：死锁
    // ========================================================================
    private static void experiment5_deadlock() throws Exception {
        System.out.println();
        System.out.println("========== 5. 死锁：应用代码类原因里最极端的一种 ==========");
        System.out.println("(两个线程以相反顺序抢两把锁 -> 必然死锁；再用程序自动发现它)");
        System.out.println();

        Object lockA = new Object();
        Object lockB = new Object();

        // 线程 1：先 A 后 B；线程 2：先 B 后 A —— 这个「顺序反了」就是死锁的根源
        Thread first = new Thread(() -> grabTwoLocks(lockA, lockB, "线程-1"), "线程-1");
        Thread second = new Thread(() -> grabTwoLocks(lockB, lockA, "线程-2"), "线程-2");
        first.setDaemon(true);      // 设成守护线程，主线程结束时 JVM 才能正常退出
        second.setDaemon(true);
        first.start();
        second.start();

        Thread.sleep(600);          // 等它们真的卡住

        // 下面这段就是 jstack 干的活，只不过用代码自动做
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = threadBean.findDeadlockedThreads();

        if (deadlockedIds == null || deadlockedIds.length == 0) {
            System.out.println("   没检测到死锁(理论上不该发生)");
            return;
        }
        System.out.println("   Found one Java-level deadlock:   <-- jstack 看到的就是这一句");
        for (ThreadInfo info : threadBean.getThreadInfo(deadlockedIds)) {
            System.out.printf("   线程「%s」 正在等锁 %s ，而这把锁被「%s」拿着%n",
                    info.getThreadName(), info.getLockName(), info.getLockOwnerName());
        }
        System.out.println("   提醒：谁也不松手，这个接口就永远不返回了 —— 不是慢，是卡死。");
        System.out.println("         实际项目里，嵌套的远程调用 + 本地锁很容易无意中搞出这种环路。");
    }

    /** 先拿第一把锁，歇一会儿，再去拿第二把 —— 这个「歇一会儿」就是给对方制造抢锁的窗口。 */
    private static void grabTwoLocks(Object firstLock, Object secondLock, String name) {
        synchronized (firstLock) {
            System.out.println("   " + name + " 拿到第一把锁，准备拿第二把");
            sleepQuietly(200);
            synchronized (secondLock) {
                System.out.println("   " + name + " 拿到第二把锁(死锁的话永远走不到这里)");
            }
        }
    }

    // ========================================================================
    // 实验 6：平均耗时 vs P99
    // ========================================================================
    private static void experiment6_avgVsP99() {
        System.out.println();
        System.out.println("========== 6. 为什么平均耗时很健康，用户还是在喊慢 ==========");
        System.out.println();

        int total = 200;
        int spikeEvery = 25;        // 每 25 次里混进 1 次毛刺（比如撞上 Full GC 或偶发慢 SQL）
        List<Long> costs = new ArrayList<>();

        for (int i = 1; i <= total; i++) {
            long start = System.nanoTime();
            if (i % spikeEvery == 0) {
                sleepQuietly(80);   // 毛刺：80 毫秒
            } else {
                burnCpu(30_000);    // 正常：不到 1 毫秒
            }
            costs.add(System.nanoTime() - start);
        }
        costs.sort(Comparator.naturalOrder());

        double avg = costs.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p99 = costs.get((int) (total * 0.99) - 1) / 1_000_000.0;
        double max = costs.get(total - 1) / 1_000_000.0;

        System.out.printf("   共 %d 次请求%n", total);
        System.out.printf("   平均耗时 : %.2f ms    <-- 看着很健康%n", avg);
        System.out.printf("   P99 耗时 : %.2f ms    <-- 这才是那 1%% 倒霉用户的真实感受%n", p99);
        System.out.printf("   最大耗时 : %.2f ms%n", max);
        System.out.printf("   结论 : 平均和 P99 差了约 %.0f 倍，只看平均值会把问题完全抹平。%n",
                p99 / Math.max(avg, 0.01));
        System.out.println("          GC 停顿、偶发慢 SQL 都是这种毛刺，所以监控一定要看 P99 / P999。");
    }

    // ========================================================================
    // 小工具
    // ========================================================================
    private static void warmUp() {
        for (int i = 0; i < 3; i++) {
            DATABASE.queryByScan(7);
            DATABASE.queryByIndex(7);
            burnCpu(300_000);
        }
    }

    /** 空转 rounds 次，用来模拟「真的在干活」。 */
    private static long burnCpu(long rounds) {
        long acc = 0;
        for (long i = 0; i < rounds; i++) {
            acc += i % 7;
        }
        cpuSink += acc;     // 丢给 volatile 变量，防止 JIT 把整段循环优化掉
        return acc;
    }

    private static double elapsedMillis(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000.0;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // 用到的几个小模型
    // ========================================================================

    /** 链路里的「一段」：名字 + 耗时，就像 SkyWalking 里的一个 span。 */
    record Span(String name, double costMillis) {
    }

    /** 一行订单。record 是 Java 16 起的语法，自动帮你写好 equals / hashCode / toString。 */
    record OrderRow(int id, int userId) {
    }

    /** 假 Redis：down = true 就当它挂了，谁都查不到东西。 */
    static class FakeRedis {
        boolean down = false;
        private final Map<String, String> data = new HashMap<>();

        String get(String key) {
            if (down) {
                return null;    // Redis 挂了，缓存全失效
            }
            return data.get(key);
        }

        void set(String key, String value) {
            data.put(key, value);
        }
    }

    /**
     * 假数据库：100 万行订单。
     * 提供两种查法：一行行翻（没索引）和按 userId 直翻（有索引），
     * 并用 scannedRows 记下「这一查到底摸了多少行」，方便对比。
     */
    static class FakeDatabase {
        private final List<OrderRow> rows = new ArrayList<>();
        private final Map<Integer, List<OrderRow>> userIdIndex = new HashMap<>();
        long scannedRows = 0;
        final AtomicLong queryCount = new AtomicLong(0);

        FakeDatabase(int rowCount) {
            for (int i = 1; i <= rowCount; i++) {
                OrderRow row = new OrderRow(i, i % 5000);
                rows.add(row);
                // 这就是「在 userId 上建索引」：把 userId 相同的行归到一起，查的时候直接翻到那一页
                userIdIndex.computeIfAbsent(row.userId, key -> new ArrayList<>()).add(row);
            }
        }

        /** 没索引：从头到尾一行行看过去。 */
        List<OrderRow> queryByScan(int userId) {
            scannedRows = 0;
            List<OrderRow> result = new ArrayList<>();
            for (OrderRow row : rows) {
                scannedRows++;
                if (row.userId == userId) {
                    result.add(row);
                }
            }
            return result;
        }

        /** 有索引：直接跳到 userId 对应的那一小撮行。 */
        List<OrderRow> queryByIndex(int userId) {
            List<OrderRow> hit = userIdIndex.getOrDefault(userId, List.of());
            scannedRows = hit.size();
            return hit;
        }

        /** 查热点商品，顺便数一下数据库被打了多少次。 */
        String queryHotProduct() {
            queryCount.incrementAndGet();
            return "商品详情-固定内容";
        }

        void resetCounters() {
            scannedRows = 0;
            queryCount.set(0);
        }
    }

    /**
     * 本地缓存兜底：Redis 挂了也不让请求直接砸到数据库。
     * 关键是 synchronized 那一段 —— 同一时刻只放一个线程去查库，
     * 其它线程在门口等，等它查完直接拿现成的（这叫 singleflight / 单飞）。
     */
    static class LocalCacheGuard {
        private final FakeRedis redis;
        private final FakeDatabase database;
        private final Map<String, String> localCache = new ConcurrentHashMap<>();
        private final Object singleFlightLock = new Object();

        LocalCacheGuard(FakeRedis redis, FakeDatabase database) {
            this.redis = redis;
            this.database = database;
        }

        String get(String key) {
            String value = localCache.get(key);
            if (value != null) {
                return value;                   // 1) 本地有，直接给，不惊动数据库
            }
            synchronized (singleFlightLock) {   // 2) 本地没有，同一时刻只放一个人进来看
                value = localCache.get(key);
                if (value != null) {
                    return value;               //    排队的人进来一看，别人已经查好了
                }
                value = redis.get(key);         // 3) 先问 Redis
                if (value == null) {
                    value = database.queryHotProduct();   // 4) Redis 也没有，才去查数据库
                }
                localCache.put(key, value);
                return value;
            }
        }
    }
}
