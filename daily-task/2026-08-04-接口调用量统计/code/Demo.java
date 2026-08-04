// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================
// 这个程序在干嘛？
// ------------------------------------------------------------
// 演示「怎么统计每个接口每分钟被调用了多少次」，完全按原题给的思路来，一共 5 个实验：
//
//   实验一：分钟桶计数        —— ConcurrentHashMap + AtomicInteger，key = 接口名 + 分钟号
//   实验二：过期桶清理        —— 只保留最近 5 分钟，防止内存越涨越大
//   实验三：本地攒批 + 批量上报 —— 每隔一小段时间 flush 一次到 Redis，省掉海量网络请求
//   实验四：Redis INCR+EXPIRE —— 跨机器精确统计时的做法
//   实验五：无锁 vs 加锁       —— 验证原题那句「别用 synchronized，CAS 才扛得住大流量」
//
// 为了不让大家真的等 5 分钟，程序里用了一个"可以手动拨快的假时钟"(FakeClock)，
// 真实项目里把 clock.nowMinute() 换成 System.currentTimeMillis() / 60000 就行，逻辑一模一样。
// ============================================================

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    public static void main(String[] args) throws Exception {
        experiment1_基础分钟桶计数();
        experiment2_过期桶清理();
        experiment3_本地攒批批量上报();
        experiment4_Redis精确计数();
        experiment5_无锁与加锁性能对比();

        System.out.println();
        System.out.println("全部实验结束。");
    }

    // ========================================================
    // 实验一：最核心的三行代码——按「接口名 + 分钟号」分桶计数
    // ========================================================
    private static void experiment1_基础分钟桶计数() throws Exception {
        printTitle("实验一：分钟桶计数（多线程一起打点，看看数得准不准）");

        FakeClock clock = new FakeClock();
        MinuteCounter counter = new MinuteCounter(clock);

        // 造一批并发请求：10 个线程，每个线程给 /user/get 打 5000 次、给 /order/create 打 2000 次
        int threadCount = 10;
        int userGetPerThread = 5000;
        int orderCreatePerThread = 2000;

        runConcurrently(threadCount, () -> {
            for (int i = 0; i < userGetPerThread; i++) {
                counter.record("/user/get");
            }
            for (int i = 0; i < orderCreatePerThread; i++) {
                counter.record("/order/create");
            }
        });

        long minute = clock.nowMinute();
        int userGetActual = counter.count("/user/get", minute);
        int orderCreateActual = counter.count("/order/create", minute);
        int userGetExpected = threadCount * userGetPerThread;
        int orderCreateExpected = threadCount * orderCreatePerThread;

        System.out.println("  当前分钟号：" + minute + "（真实项目里就是 System.currentTimeMillis() / 60000）");
        System.out.println("  /user/get      期望 " + userGetExpected + " 次，实际 " + userGetActual + " 次  "
                + tick(userGetActual == userGetExpected));
        System.out.println("  /order/create  期望 " + orderCreateExpected + " 次，实际 " + orderCreateActual + " 次  "
                + tick(orderCreateActual == orderCreateExpected));
        System.out.println("  结论：10 个线程同时猛怼，一次都没数丢——这就是 AtomicInteger（不会卡壳的取号机）的功劳。");
    }

    // ========================================================
    // 实验二：时间往前走，老桶要定期清掉，不然内存会一直涨
    // ========================================================
    private static void experiment2_过期桶清理() {
        printTitle("实验二：过期桶清理（只留最近 5 分钟）");

        FakeClock clock = new FakeClock();
        MinuteCounter counter = new MinuteCounter(clock);

        // 模拟连续 8 分钟，每分钟都有请求进来
        for (int minuteIndex = 0; minuteIndex < 8; minuteIndex++) {
            int callTimes = 100 + minuteIndex * 10;
            for (int i = 0; i < callTimes; i++) {
                counter.record("/user/get");
            }
            clock.forwardOneMinute();   // 把假时钟往前拨一分钟
        }
        clock.backwardOneMinute();      // 拨回最后一个真正有数据的分钟

        System.out.println("  跑了 8 分钟后，内存里攒下的桶数量：" + counter.bucketCount() + " 个");
        System.out.println("  清理前的明细（分钟号 -> 调用次数）：");
        printBuckets(counter);

        int removed = counter.cleanExpired(5);   // 只保留最近 5 分钟

        System.out.println("  执行清理，删掉了 " + removed + " 个过期桶，还剩 " + counter.bucketCount() + " 个");
        System.out.println("  清理后的明细：");
        printBuckets(counter);
        System.out.println("  结论：" + tick(counter.bucketCount() == 5) + " 桶数稳定在 5 个，内存不会无限涨。");
        System.out.println("  线上就是靠一个每分钟跑一次的定时任务干这件事，忘了写它就等着 OOM。");
    }

    // ========================================================
    // 实验三：本地先攒着，每隔一会儿打包发一次，省掉海量网络请求
    // ========================================================
    private static void experiment3_本地攒批批量上报() throws Exception {
        printTitle("实验三：本地攒批 + 批量上报（Sentinel 的做法）");

        FakeClock clock = new FakeClock();
        FakeRedis redis = new FakeRedis();
        BatchReporter reporter = new BatchReporter(redis, clock);

        // 模拟 3 台机器，每台并发打 10000 次点
        int machineCount = 3;
        int callsPerMachine = 10000;

        runConcurrently(machineCount, () -> {
            for (int i = 0; i < callsPerMachine; i++) {
                reporter.record("/user/get");   // 这一步只动本地内存，纳秒级，不走网络
            }
        });

        System.out.println("  3 台机器共打了 " + (machineCount * callsPerMachine) + " 次点，"
                + "此时已发生的网络请求数：" + reporter.networkCallCount() + " 次（还没 flush，所以是 0）");

        reporter.flush();   // 定时线程每 10 秒会调一次，这里手动触发

        long total = redis.get(MinuteCounter.buildKey("/user/get", clock.nowMinute()));
        System.out.println("  flush 之后 Redis 里的值：" + total);
        System.out.println("  累计网络请求数：" + reporter.networkCallCount() + " 次");
        System.out.println("  结论：" + tick(total == machineCount * callsPerMachine)
                + " 一次都没少，但 30000 次调用只发了 " + reporter.networkCallCount() + " 次网络请求。");
        System.out.println("  代价是最多 10 秒的延迟——看大盘趋势完全够用，所以这是最常用的方案。");
    }

    // ========================================================
    // 实验四：要求跨机器一次都不能错时，直接写 Redis 的 INCR + EXPIRE
    // ========================================================
    private static void experiment4_Redis精确计数() throws Exception {
        printTitle("实验四：Redis INCR + EXPIRE（跨机器、要求绝对精确）");

        FakeClock clock = new FakeClock();
        FakeRedis redis = new FakeRedis();
        String key = MinuteCounter.buildKey("/pay/submit", clock.nowMinute());

        // 5 台机器，每台并发调 2000 次，每次都真的往 Redis 写一条
        int machineCount = 5;
        int callsPerMachine = 2000;

        runConcurrently(machineCount, () -> {
            for (int i = 0; i < callsPerMachine; i++) {
                redis.incr(key);            // 对应 Redis 命令：INCR key
                redis.expire(key, 300);     // 对应 Redis 命令：EXPIRE key 300（5 分钟后自动删）
            }
        });

        long total = redis.get(key);
        System.out.println("  key = " + key);
        System.out.println("  5 台机器各调 2000 次，Redis 里的值：" + total
                + "  " + tick(total == machineCount * callsPerMachine));
        System.out.println("  这个 key 的过期时间：" + redis.ttl(key) + " 秒（到点自动消失，不用自己写清理任务）");
        System.out.println("  代价：每次调用都要走一趟网络，量大时 Redis 会被打爆，所以只在防刷/计费这类场景用。");
        System.out.println("  ⚠️ 提醒：INCR 和 EXPIRE 是两条命令，中间崩了会留下永不过期的 key，");
        System.out.println("     生产上要用 Lua 脚本把两条打包成一个原子操作。");
    }

    // ========================================================
    // 实验五：验证原题那句「别用 synchronized」
    // ========================================================
    private static void experiment5_无锁与加锁性能对比() throws Exception {
        printTitle("实验五：AtomicInteger（无锁 CAS） vs synchronized（加锁）");

        int threadCount = 8;
        int timesPerThread = 500_000;
        int expected = threadCount * timesPerThread;

        // 先各空跑一轮"热身"：JVM 要跑过几遍才会把代码优化成机器码，
        // 不热身的话先跑的那个会吃亏，比出来的数不公平。
        AtomicInteger warmupAtomic = new AtomicInteger();
        LockedCounter warmupLocked = new LockedCounter();
        runConcurrently(threadCount, () -> {
            for (int i = 0; i < 100_000; i++) {
                warmupAtomic.incrementAndGet();
                warmupLocked.increase();
            }
        });

        // 方式 A：AtomicInteger —— 自助扫码闸机，大家同时刷，冲突了重刷一下
        AtomicInteger atomicCounter = new AtomicInteger();
        long atomicCostMillis = measureMillis(() -> runConcurrentlyQuietly(threadCount, () -> {
            for (int i = 0; i < timesPerThread; i++) {
                atomicCounter.incrementAndGet();
            }
        }));

        // 方式 B：synchronized —— 只有一把钥匙的厕所，后面的人全排队
        LockedCounter lockedCounter = new LockedCounter();
        long lockedCostMillis = measureMillis(() -> runConcurrentlyQuietly(threadCount, () -> {
            for (int i = 0; i < timesPerThread; i++) {
                lockedCounter.increase();
            }
        }));

        System.out.println("  8 个线程各自 +1 共 " + expected + " 次（已做 JIT 热身，结果更公平）：");
        System.out.println("    AtomicInteger  结果 " + atomicCounter.get()
                + "  " + tick(atomicCounter.get() == expected) + "  耗时 " + atomicCostMillis + " ms");
        System.out.println("    synchronized   结果 " + lockedCounter.get()
                + "  " + tick(lockedCounter.get() == expected) + "  耗时 " + lockedCostMillis + " ms");
        if (atomicCostMillis > 0) {
            System.out.printf("    加锁耗时是无锁的 %.2f 倍%n", lockedCostMillis / (double) atomicCostMillis);
        }
        System.out.println("  结论：两者结果都准，差别在耗时上。这台机器上加锁更慢，");
        System.out.println("       而且竞争线程越多、锁持有时间越长，差距会拉得越大（真实业务里远不止 count++ 这一行）。");
        System.out.println("       统计这种「顺手做的小事」绝不该拖慢主流程，所以选无锁的 AtomicInteger。");
    }

    // ========================================================
    // 下面是各个小零件的实现
    // ========================================================

    /**
     * 一个可以手动拨快的假时钟。
     * 真实项目里不需要它，直接用 System.currentTimeMillis() / 60000 即可；
     * 这里只是为了让演示不用真的等 5 分钟。
     */
    static class FakeClock {
        private final AtomicLong currentMinute = new AtomicLong(29_123_456L);

        long nowMinute() {
            return currentMinute.get();
        }

        void forwardOneMinute() {
            currentMinute.incrementAndGet();
        }

        void backwardOneMinute() {
            currentMinute.decrementAndGet();
        }
    }

    /**
     * 核心计数器：一分钟一个"桶"，桶里放一个不会数错的计数器。
     * 对应原题那三行代码。
     */
    static class MinuteCounter {
        // key 形如 "api:/user/get:29123456"，value 是那一分钟的调用次数
        private final ConcurrentHashMap<String, AtomicInteger> buckets = new ConcurrentHashMap<>();
        private final FakeClock clock;

        MinuteCounter(FakeClock clock) {
            this.clock = clock;
        }

        /** 打一次点：接口被调用时喊一嗓子，这里就 +1 */
        void record(String apiName) {
            String key = buildKey(apiName, clock.nowMinute());
            // computeIfAbsent = "没有就新建一个桶，有就直接拿现成的"
            buckets.computeIfAbsent(key, unusedKey -> new AtomicInteger()).incrementAndGet();
        }

        /** 拼 key：接口名 + 分钟号，两个接口 / 两个分钟互不干扰 */
        static String buildKey(String apiName, long minute) {
            return "api:" + apiName + ":" + minute;
        }

        /** 查某个接口某一分钟的调用次数 */
        int count(String apiName, long minute) {
            AtomicInteger bucket = buckets.get(buildKey(apiName, minute));
            return bucket == null ? 0 : bucket.get();
        }

        /**
         * 清理过期桶：只保留最近 keepMinutes 分钟的数据。
         * 线上就是一个每分钟跑一次的定时任务在调它。
         */
        int cleanExpired(int keepMinutes) {
            long oldestMinuteToKeep = clock.nowMinute() - keepMinutes + 1;
            int removedCount = 0;
            // 先拷一份 key 出来再删，避免边遍历边改
            for (String key : new ArrayList<>(buckets.keySet())) {
                if (parseMinute(key) < oldestMinuteToKeep) {
                    buckets.remove(key);
                    removedCount++;
                }
            }
            return removedCount;
        }

        /** 从 key 里把分钟号抠出来（key 的最后一段就是分钟号） */
        static long parseMinute(String key) {
            return Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
        }

        /** 把当前所有桶按分钟号排好序，方便打印查看 */
        Map<Long, Integer> snapshotByMinute() {
            Map<Long, Integer> sorted = new TreeMap<>();
            buckets.forEach((key, value) -> sorted.put(parseMinute(key), value.get()));
            return sorted;
        }

        int bucketCount() {
            return buckets.size();
        }
    }

    /**
     * 一个假的 Redis，用来演示 INCR / EXPIRE 两条命令。
     * 真实项目里换成 RedisTemplate 调用即可。
     */
    static class FakeRedis {
        private final ConcurrentHashMap<String, AtomicLong> values = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Integer> ttlSeconds = new ConcurrentHashMap<>();

        /** 对应 Redis 的 INCR：原子自增 1，多台机器一起调也不会算错 */
        long incr(String key) {
            return incrBy(key, 1);
        }

        /** 对应 Redis 的 INCRBY：一次加一批，攒批上报时用这个 */
        long incrBy(String key, long delta) {
            return values.computeIfAbsent(key, unusedKey -> new AtomicLong()).addAndGet(delta);
        }

        /** 对应 Redis 的 EXPIRE：给这个 key 贴张便利贴，到点自动销毁 */
        void expire(String key, int seconds) {
            ttlSeconds.put(key, seconds);
        }

        long get(String key) {
            AtomicLong value = values.get(key);
            return value == null ? 0L : value.get();
        }

        int ttl(String key) {
            return ttlSeconds.getOrDefault(key, -1);
        }
    }

    /**
     * 攒批上报器：请求进来只动本地内存，攒够一段时间再打包发给 Redis。
     * 30000 次调用能压缩成 1~2 次网络请求，这就是 Sentinel 的思路。
     */
    static class BatchReporter {
        private final ConcurrentHashMap<String, AtomicInteger> stagingArea = new ConcurrentHashMap<>();
        private final FakeRedis redis;
        private final FakeClock clock;
        private final AtomicInteger networkCallCount = new AtomicInteger();

        BatchReporter(FakeRedis redis, FakeClock clock) {
            this.redis = redis;
            this.clock = clock;
        }

        /** 业务线程调这个：只在本地内存 +1，不走网络 */
        void record(String apiName) {
            String key = MinuteCounter.buildKey(apiName, clock.nowMinute());
            stagingArea.computeIfAbsent(key, unusedKey -> new AtomicInteger()).incrementAndGet();
        }

        /** 后台线程每 10 秒调一次：把攒下的量一次性推给 Redis */
        void flush() {
            for (String key : new ArrayList<>(stagingArea.keySet())) {
                AtomicInteger bucket = stagingArea.get(key);
                if (bucket == null) {
                    continue;
                }
                // getAndSet(0) = "把桶里的数全部倒出来，同时把桶清空"，倒的过程中新来的请求不会丢
                int accumulated = bucket.getAndSet(0);
                if (accumulated > 0) {
                    redis.incrBy(key, accumulated);
                    redis.expire(key, 300);
                    networkCallCount.incrementAndGet();
                }
            }
        }

        int networkCallCount() {
            return networkCallCount.get();
        }
    }

    /** 用 synchronized 加锁的计数器，只在实验五里作为反面对照 */
    static class LockedCounter {
        private int count = 0;

        synchronized void increase() {
            count++;
        }

        synchronized int get() {
            return count;
        }
    }

    // ========================================================
    // 一些打印和跑并发的小工具
    // ========================================================

    /** 开 threadCount 个线程同时跑同一段活儿，等全部跑完才返回 */
    private static void runConcurrently(int threadCount, Runnable job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);   // 发令枪：让所有线程尽量同时起跑
        CountDownLatch finishLine = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    job.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }
        startGun.countDown();
        finishLine.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** 同上，但把受检异常吞掉，方便写在计时的 lambda 里 */
    private static void runConcurrentlyQuietly(int threadCount, Runnable job) {
        try {
            runConcurrently(threadCount, job);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 测一段代码跑了多少毫秒 */
    private static long measureMillis(Runnable job) {
        long start = System.nanoTime();
        job.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println(title);
        System.out.println("==================================================");
    }

    private static void printBuckets(MinuteCounter counter) {
        List<String> lines = new ArrayList<>();
        counter.snapshotByMinute().forEach((minute, times) -> lines.add(minute + " -> " + times + " 次"));
        System.out.println("    " + String.join("  |  ", lines));
    }

    private static String tick(boolean ok) {
        return ok ? "[正好对上]" : "[对不上!]";
    }
}
