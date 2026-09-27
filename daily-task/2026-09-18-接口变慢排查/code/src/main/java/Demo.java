// 【这个程序在干嘛】题目：接口变慢了应该怎么排查？导致接口变慢的原因有哪些？
// 接口变慢不是「一个问题」，而是「系统某一站亮了红灯」，排查的核心是分层测量，不是靠猜。
// 6 个纯 JDK 小实验：1 链路耗时埋点（迷你版 SkyWalking，量出谁占大头）/ 2 慢 SQL 加索引前后对比 /
// 3 外部依赖不设超时拖死线程池 / 4 缓存击穿用本地缓存兜底 / 5 死锁怎么自动发现 / 6 平均耗时 vs P99。
//
// 不需要中间件。编译：mvn -o -q compile   运行：mvn -o exec:java

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    /** 一张 100 万行的假订单表，实验 1、2、4 共用。 */
    static final FakeDatabase DATABASE = new FakeDatabase(1_000_000);

    /** 实验 1 里「缓存命中」那一站取的值（真实项目里这一站是 Redis）。 */
    static final Map<String, String> CACHE = Map.of("hot:product:1", "缓存里的商品");

    /** 用来接住 burnCpu 的结果，防止 JIT 把空循环当废代码优化掉。 */
    static volatile long cpuSink;

    record Span(String name, double costMillis) {
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {          // 先热身，让 JVM 把热点代码编译好，后面耗时才准
            DATABASE.queryByScan(7);
            DATABASE.queryByIndex(7);
            burnCpu(300_000);
        }
        experiment1Trace();
        experiment2SlowSql();
        experiment3NoTimeout();
        experiment4CacheBreakdown();
        experiment5Deadlock();
        experiment6AvgVsP99();
        System.out.println("\n========== 全部实验结束 ==========");
    }

    // ===================== 实验 1：链路耗时埋点 =====================
    static void experiment1Trace() {
        System.out.println("========== 1. 链路耗时埋点：先量出每一站花了多久 ==========");
        System.out.println("(真实项目里这一步由 SkyWalking / Prometheus 自动完成，这里手写一个迷你版)");

        List<Span> spans = new ArrayList<>();
        spans.add(new Span("网关鉴权 + 本地计算", millisOf(() -> burnCpu(100_000))));
        spans.add(new Span("Redis 查缓存", millisOf(() -> CACHE.get("hot:product:1"))));
        spans.add(new Span("MySQL 查询(没索引，查了 2 次)", millisOf(() -> {
            DATABASE.queryByScan(7);
            DATABASE.queryByScan(7);
        })));
        spans.add(new Span("调用第三方 HTTP", millisOf(() -> sleepQuietly(1))));
        double total = spans.stream().mapToDouble(Span::costMillis).sum();
        spans.forEach(span -> System.out.printf("   %s : 耗时 %.2f ms , 占比 %.1f%%%n",
                span.name(), span.costMillis(), span.costMillis() / total * 100));
        Span slowest = spans.stream().max(Comparator.comparingDouble(Span::costMillis)).orElseThrow();
        System.out.printf("   总耗时 %.2f ms ，最大头是「%s」占 %.1f%% -> 就先查它%n",
                total, slowest.name(), slowest.costMillis() / total * 100);
        System.out.println("   提醒：别一上来就翻代码。先量出谁占大头，再往下钻。");
    }

    /** 量一段代码的耗时（毫秒）—— 链路追踪埋点干的就是这件事。 */
    static double millisOf(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    // ===================== 实验 2：慢 SQL =====================
    static void experiment2SlowSql() {
        System.out.println("\n========== 2. 慢 SQL：加索引前后差多少 ==========");
        List<OrderRow> byScan = new ArrayList<>();
        List<OrderRow> byIndex = new ArrayList<>();
        double scanMillis = millisOf(() -> byScan.addAll(DATABASE.queryByScan(7)));
        long scanRows = DATABASE.scannedRows;
        double indexMillis = millisOf(() -> byIndex.addAll(DATABASE.queryByIndex(7)));
        long indexRows = DATABASE.scannedRows;

        System.out.printf("   全表扫描 : 摸了 %d 行 , 耗时 %.2f ms , 命中 %d 条%n", scanRows, scanMillis, byScan.size());
        System.out.printf("   走索引   : 摸了 %d 行 , 耗时 %.3f ms , 命中 %d 条%n", indexRows, indexMillis, byIndex.size());
        System.out.printf("   结论 : 少摸 %.0f 倍的行，快了约 %.0f 倍，两边结果%s%n",
                scanRows * 1.0 / Math.max(indexRows, 1),
                scanMillis / Math.max(indexMillis, 0.001),
                byScan.equals(byIndex) ? "一致" : "不一致");
        System.out.println("   对应实验 1：数据库那格要是占了大头，先去看 EXPLAIN 有没有走索引。");
    }

    // ===================== 实验 3：外部依赖不设超时 =====================
    static void experiment3NoTimeout() throws Exception {
        System.out.println("\n========== 3. 外部依赖不设超时：一个慢接口拖死整个应用 ==========");
        System.out.println("   (把 Tomcat 的 200 个线程缩小成 4 个，方便看清线程池怎么被占满)");
        long noTimeoutWait = blockedLocalTaskWait(1200, Integer.MAX_VALUE);
        long timeoutWait = blockedLocalTaskWait(1200, 200);
        System.out.printf("   [A] 不设超时      : 一个跟第三方毫无关系的本地接口，等了 %d ms 才轮到线程%n", noTimeoutWait);
        System.out.printf("   [B] 设 200ms 超时 : 同一个本地接口，只等了 %d ms%n", timeoutWait);
        System.out.printf("   结论 : 配好超时后本地接口少等 %.1f 倍。Feign / RestTemplate 默认不设超时，%n",
                noTimeoutWait * 1.0 / Math.max(timeoutWait, 1));
        System.out.println("          对方 30 秒不回你就等 30 秒，几十个请求一来线程全被占满，不相关的接口也一起堵死。");
    }

    /** 4 个线程的池模拟缩小版 Tomcat：4 个线程全被慢第三方占住后，纯本地任务要等多久才拿到线程。 */
    static long blockedLocalTaskWait(int thirdPartyCostMillis, int timeoutMillis) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        for (int i = 0; i < 4; i++) {
            // 对方 thirdPartyCostMillis 才回，你最多等 timeoutMillis，到点就走人
            pool.execute(() -> sleepQuietly(Math.min(thirdPartyCostMillis, timeoutMillis)));
        }
        Thread.sleep(60);   // 等它们真的进到「等第三方」的状态
        long start = System.currentTimeMillis();
        pool.submit(() -> "本地接口返回").get();
        long waited = System.currentTimeMillis() - start;
        pool.shutdownNow();
        return waited;
    }

    // ===================== 实验 4：缓存击穿 =====================
    static void experiment4CacheBreakdown() throws Exception {
        System.out.println("\n========== 4. 缓存击穿：缓存没命中，请求全砸到数据库 ==========");
        String hotKey = "hot:product:1";
        int threadCount = 100;

        // 场景 A：缓存挂了谁都不兜底，100 个请求一起去查库
        DATABASE.queryCount.set(0);
        runConcurrent(threadCount, () -> DATABASE.queryHotProduct());
        long hitsWithoutGuard = DATABASE.queryCount.get();
        System.out.printf("   [A] 没有兜底    : 数据库被打了 %d 次%n", hitsWithoutGuard);

        // 场景 B：本地缓存兜底。computeIfAbsent 是原子的，同一个 key 只会放一个线程去查库，
        // 其它线程等它查完直接拿结果 —— 这就是 singleflight（单飞）
        DATABASE.queryCount.set(0);
        Map<String, String> localCache = new ConcurrentHashMap<>();
        runConcurrent(threadCount, () -> localCache.computeIfAbsent(hotKey, key -> DATABASE.queryHotProduct()));
        long hitsWithGuard = DATABASE.queryCount.get();
        System.out.printf("   [B] 本地缓存兜底 : 数据库只被打了 %d 次%n", hitsWithGuard);
        System.out.printf("   结论 : 数据库压力从 %d 次降到 %d 次，100 个用户拿到的还是同一份数据。%n",
                hitsWithoutGuard, hitsWithGuard);
    }

    /** 让 threadCount 个线程同时执行 task —— 就像热点 key 过期那一瞬间涌进来一堆请求。 */
    static void runConcurrent(int threadCount, Runnable task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    ready.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();
    }

    // ===================== 实验 5：死锁 =====================
    static void experiment5Deadlock() throws Exception {
        System.out.println("\n========== 5. 死锁：应用代码类原因里最极端的一种 ==========");
        Object lockA = new Object();
        Object lockB = new Object();
        // 线程 1 先 A 后 B，线程 2 先 B 后 A —— 这个「顺序反了」就是死锁的根源。
        // 必须设成守护线程：它们会永远卡住，不设的话 JVM 退不出去。
        startDaemon("线程-1", () -> grabTwoLocks(lockA, lockB, "线程-1"));
        startDaemon("线程-2", () -> grabTwoLocks(lockB, lockA, "线程-2"));
        Thread.sleep(600);   // 等它们真的卡住
        // 下面这段就是 jstack 干的活，只不过用代码自动做
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = threadBean.findDeadlockedThreads();
        if (deadlockedIds == null || deadlockedIds.length == 0) {
            System.out.println("   没检测到死锁(理论上不该发生)");
            return;
        }
        System.out.println("   Found one Java-level deadlock:   <-- jstack 看到的就是这一句");
        for (ThreadInfo info : threadBean.getThreadInfo(deadlockedIds)) {
            System.out.printf("   线程「%s」在等锁 %s ，这把锁被「%s」拿着%n",
                    info.getThreadName(), info.getLockName(), info.getLockOwnerName());
        }
        System.out.println("   提醒：谁也不松手，这个接口就永远不返回了 —— 不是慢，是卡死。");
    }

    static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** 先拿第一把锁，歇一会儿再去拿第二把 —— 这个「歇一会儿」就是给对方制造抢锁的窗口。 */
    static void grabTwoLocks(Object firstLock, Object secondLock, String name) {
        synchronized (firstLock) {
            System.out.println("   " + name + " 拿到第一把锁，准备拿第二把");
            sleepQuietly(200);
            synchronized (secondLock) {
                System.out.println("   " + name + " 拿到第二把锁(死锁的话永远走不到这里)");
            }
        }
    }

    // ===================== 实验 6：平均 vs P99 =====================
    static void experiment6AvgVsP99() {
        System.out.println("\n========== 6. 为什么平均耗时很健康，用户还是在喊慢 ==========");
        int total = 200;
        List<Long> costs = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            long start = System.nanoTime();
            if (i % 25 == 0) {
                sleepQuietly(80);   // 毛刺：每 25 次混进 1 次，比如撞上 Full GC 或偶发慢 SQL
            } else {
                burnCpu(30_000);    // 正常：不到 1 毫秒
            }
            costs.add(System.nanoTime() - start);
        }
        costs.sort(Comparator.naturalOrder());

        double average = costs.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        System.out.printf("   共 %d 次请求，平均耗时 %.2f ms    <-- 看着很健康%n", total, average);
        System.out.printf("   P99 耗时 %.2f ms    <-- 这才是那 1%% 倒霉用户的真实感受%n", percentile(costs, 99));
        System.out.printf("   最大耗时 %.2f ms%n", costs.get(total - 1) / 1_000_000.0);
        System.out.printf("   结论 : 平均和 P99 差了约 %.0f 倍，只看平均值会把问题完全抹平。%n",
                percentile(costs, 99) / Math.max(average, 0.01));
        System.out.println("          GC 停顿、偶发慢 SQL 都是这种毛刺，所以监控一定要看 P99 / P999。");
    }

    /** 分位数：耗时从小到大排好后取第 p 百分位那个值。P99 就是「最慢那 1% 的起点」。 */
    static double percentile(List<Long> sortedCosts, int p) {
        int index = (int) Math.ceil(sortedCosts.size() * p / 100.0) - 1;
        return sortedCosts.get(Math.max(index, 0)) / 1_000_000.0;
    }

    /** 空转 rounds 次，模拟「真的在干活」。 */
    static long burnCpu(long rounds) {
        long acc = 0;
        for (long i = 0; i < rounds; i++) {
            acc += i % 7;
        }
        cpuSink += acc;   // 丢给 volatile 变量，防止 JIT 把整段循环优化掉
        return acc;
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===================== 用到的几个小模型 =====================
    record OrderRow(int id, int userId) {
    }

    /** 假数据库：两种查法分别对应 EXPLAIN 的 type=ALL（全表扫）和 type=ref（走索引），并记下摸了多少行。 */
    static class FakeDatabase {
        private final List<OrderRow> rows = new ArrayList<>();
        private final Map<Integer, List<OrderRow>> userIdIndex = new HashMap<>();
        long scannedRows;
        final AtomicLong queryCount = new AtomicLong();

        FakeDatabase(int rowCount) {
            for (int i = 1; i <= rowCount; i++) {
                OrderRow row = new OrderRow(i, i % 5000);
                rows.add(row);
                // 建索引：把 userId 相同的行归到一起，查的时候直接翻到那一页
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

        String queryHotProduct() {
            queryCount.incrementAndGet();
            return "商品详情-固定内容";
        }
    }
}
