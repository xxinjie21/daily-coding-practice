// 编译运行：mvn -q compile exec:java
//
// 演示「怎么统计每个接口每分钟被调用了多少次」，一共 5 个实验：
//   实验一：分钟桶计数         —— ConcurrentHashMap + AtomicInteger，key = 接口名 + 分钟号
//   实验二：过期桶清理         —— 只保留最近 5 分钟，不然 Map 只进不出，跑几天就 OOM
//   实验三：本地攒批 + 批量上报 —— 请求只动本地内存，攒一会儿再打包 INCRBY 给 Redis
//   实验四：Redis INCR + EXPIRE —— 跨机器精确统计；生产上用 Lua 把两条命令打包成原子操作
//   实验五：无锁 vs 加锁       —— 验证「别用 synchronized，CAS 才扛得住大流量」
// 前置条件：本机 6379 有 Redis。为了不用真等 5 分钟，程序里有个能手动拨快的假时钟。

import redis.clients.jedis.Jedis;

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
        experiment1_分钟桶计数();
        experiment2_过期桶清理();
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            experiment3_本地攒批批量上报(jedis);
            experiment4_Redis精确计数(jedis);
        }
        experiment5_无锁与加锁性能对比();
        System.out.println("\n全部实验结束。");
    }

    /** 实验一：最核心的三行代码——按「接口名 + 分钟号」分桶计数 */
    static void experiment1_分钟桶计数() throws Exception {
        printTitle("实验一：分钟桶计数（多线程一起打点，看看数得准不准）");

        Clock clock = new Clock();
        MinuteCounter counter = new MinuteCounter(clock);
        int threadCount = 10;
        int userGetPerThread = 5000;
        int orderCreatePerThread = 2000;

        runConcurrently(threadCount, () -> {
            for (int i = 0; i < userGetPerThread; i++) counter.record("/user/get");
            for (int i = 0; i < orderCreatePerThread; i++) counter.record("/order/create");
        });

        long minute = clock.nowMinute();
        int userGetActual = counter.count("/user/get", minute);
        int orderCreateActual = counter.count("/order/create", minute);
        int userGetExpected = threadCount * userGetPerThread;
        int orderCreateExpected = threadCount * orderCreatePerThread;
        System.out.println("  当前分钟号：" + minute + "（真实项目里就是 System.currentTimeMillis() / 60000）");
        System.out.println("  /user/get 期望 " + userGetExpected + " 实际 " + userGetActual + "  " + tick(userGetActual == userGetExpected)
                + "；/order/create 期望 " + orderCreateExpected + " 实际 " + orderCreateActual + "  " + tick(orderCreateActual == orderCreateExpected));
        System.out.println("  结论：10 个线程同时猛怼，一次都没数丢——这就是 AtomicInteger（不会卡壳的取号机）的功劳。");
    }

    /** 实验二：时间往前走，老桶要定期清掉，不然内存一直涨 */
    static void experiment2_过期桶清理() {
        printTitle("实验二：过期桶清理（只留最近 5 分钟）");

        Clock clock = new Clock();
        MinuteCounter counter = new MinuteCounter(clock);
        for (int minuteIndex = 0; minuteIndex < 8; minuteIndex++) {
            for (int i = 0; i < 100 + minuteIndex * 10; i++) counter.record("/user/get");
            clock.forwardOneMinute();
        }
        clock.backwardOneMinute();

        System.out.println("  跑了 8 分钟，内存里攒下的桶数量：" + counter.bucketCount()
                + " 个，清理前（分钟号=次数）：" + counter.snapshotByMinute());
        System.out.println("  执行清理，删掉 " + counter.cleanExpired(5) + " 个过期桶，还剩 " + counter.bucketCount()
                + " 个，清理后：" + counter.snapshotByMinute());
        System.out.println("  结论：" + tick(counter.bucketCount() == 5) + " 桶数稳定在 5 个，内存不会无限涨。");
        System.out.println("  线上就是靠一个每分钟跑一次的定时任务干这件事，忘了写它就等着 OOM。");
    }

    /** 实验三：本地先攒着，每隔一会儿打包发一次，省掉海量网络请求 */
    static void experiment3_本地攒批批量上报(Jedis jedis) throws Exception {
        printTitle("实验三：本地攒批 + 批量上报（Sentinel 的做法）");

        Clock clock = new Clock();
        String key = MinuteCounter.buildKey("/user/get", clock.nowMinute());
        jedis.del(key);
        BatchReporter reporter = new BatchReporter(jedis, clock);

        int machineCount = 3;
        int callsPerMachine = 10000;
        runConcurrently(machineCount, () -> {
            for (int i = 0; i < callsPerMachine; i++) {
                reporter.record("/user/get");   // 只动本地内存，纳秒级，不走网络
            }
        });

        System.out.println("  3 台机器共打了 " + machineCount * callsPerMachine + " 次点，此时网络请求数："
                + reporter.networkCallCount.get() + " 次（还没 flush，所以是 0）");
        reporter.flush();
        System.out.println("  flush 之后 Redis 里的值：" + jedis.get(key)
                + "  " + tick(Long.parseLong(jedis.get(key)) == machineCount * callsPerMachine)
                + "，30000 次调用累计只发了 " + reporter.networkCallCount.get() + " 次网络请求。");
        System.out.println("  代价是最多 10 秒的延迟——看大盘趋势完全够用，所以这是最常用的方案。");
    }

    /** 实验四：要求跨机器一次都不能错时，直接写 Redis 的 INCR + EXPIRE */
    static void experiment4_Redis精确计数(Jedis jedis) throws Exception {
        printTitle("实验四：Redis INCR + EXPIRE（跨机器、要求绝对精确）");

        Clock clock = new Clock();
        String key = MinuteCounter.buildKey("/pay/submit", clock.nowMinute());
        jedis.del(key);

        int machineCount = 5;
        int callsPerMachine = 200;
        runConcurrently(machineCount, () -> {
            try (Jedis workerJedis = new Jedis("localhost", 6379)) {
                for (int i = 0; i < callsPerMachine; i++) {
                    workerJedis.incr(key);          // INCR key：原子自增，多台机器一起写也不会算错
                    workerJedis.expire(key, 300);   // EXPIRE key 300：5 分钟后自动删，不用自己写清理任务
                }
            }
        });

        long total = Long.parseLong(jedis.get(key));
        System.out.println("  key = " + key + "，5 台机器各调 200 次后 Redis 里的值：" + total
                + "  " + tick(total == machineCount * callsPerMachine)
                + "，TTL = " + jedis.ttl(key) + " 秒（到点自动消失）");
        System.out.println("  代价：每次调用都走一趟网络，量大时 Redis 会被打爆，所以只在防刷/计费这类场景用。");

        // INCR 和 EXPIRE 是两条命令，中间崩了会留下永不过期的 key，所以生产上用 Lua 打包成一个原子操作
        String lua = "local c = redis.call('INCR', KEYS[1]) "
                + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return c";
        System.out.println("  换成 Lua 打包后，一次调用返回 " + jedis.eval(lua, List.of(key), List.of("300"))
                + "，TTL 依然是 " + jedis.ttl(key) + " 秒。");
    }

    /** 实验五：验证原题那句「别用 synchronized」 */
    static void experiment5_无锁与加锁性能对比() throws Exception {
        printTitle("实验五：AtomicInteger（无锁 CAS） vs synchronized（加锁）");

        int threadCount = 8;
        int timesPerThread = 500_000;
        int expected = threadCount * timesPerThread;

        // 先空跑一轮热身：JVM 要跑过几遍才会把代码优化成机器码，不热身先跑的那个会吃亏
        AtomicInteger warmupAtomic = new AtomicInteger();
        LockedCounter warmupLocked = new LockedCounter();
        runConcurrently(threadCount, () -> {
            for (int i = 0; i < 100_000; i++) { warmupAtomic.incrementAndGet(); warmupLocked.increase(); }
        });

        // AtomicInteger = 自助扫码闸机，冲突了重刷一下；synchronized = 只有一把钥匙的厕所，后面的人全排队
        AtomicInteger atomicCounter = new AtomicInteger();
        long atomicMillis = runConcurrently(threadCount, () -> {
            for (int i = 0; i < timesPerThread; i++) atomicCounter.incrementAndGet();
        });
        LockedCounter lockedCounter = new LockedCounter();
        long lockedMillis = runConcurrently(threadCount, () -> {
            for (int i = 0; i < timesPerThread; i++) lockedCounter.increase();
        });

        System.out.println("  8 个线程各自 +1 共 " + expected + " 次（已做 JIT 热身，结果更公平）：");
        System.out.printf("    AtomicInteger  结果 %d  %s  耗时 %d ms%n",
                atomicCounter.get(), tick(atomicCounter.get() == expected), atomicMillis);
        System.out.printf("    synchronized   结果 %d  %s  耗时 %d ms%n",
                lockedCounter.get(), tick(lockedCounter.get() == expected), lockedMillis);
        if (atomicMillis > 0) System.out.printf("    加锁耗时是无锁的 %.2f 倍%n", lockedMillis / (double) atomicMillis);
        System.out.println("  结论：两者结果都准，差别在耗时上。竞争越激烈、锁里活儿越重，差距拉得越大；");
        System.out.println("       统计这种「顺手做的小事」绝不该拖慢主流程，所以选无锁的 AtomicInteger。");
    }

    // ========== 各个小零件 ==========

    /** 一个可以手动拨快的假时钟。真实项目里不需要它，直接用 System.currentTimeMillis() / 60000 */
    static class Clock {
        final AtomicLong currentMinute = new AtomicLong(29_123_456L);
        long nowMinute() { return currentMinute.get(); }
        void forwardOneMinute() { currentMinute.incrementAndGet(); }
        void backwardOneMinute() { currentMinute.decrementAndGet(); }
    }

    /** 核心计数器：一分钟一个「桶」，桶里放一个不会数错的计数器 */
    static class MinuteCounter {
        final ConcurrentHashMap<String, AtomicInteger> buckets = new ConcurrentHashMap<>();
        final Clock clock;

        MinuteCounter(Clock clock) { this.clock = clock; }
        /** 打一次点：接口被调用时喊一嗓子，这里 +1 */
        void record(String apiName) {
            // computeIfAbsent = 「没有就新建一个桶，有就直接拿现成的」
            buckets.computeIfAbsent(buildKey(apiName, clock.nowMinute()), key -> new AtomicInteger())
                    .incrementAndGet();
        }

        /** 拼 key：接口名 + 分钟号，两个接口 / 两个分钟互不干扰 */
        static String buildKey(String apiName, long minute) { return "api:" + apiName + ":" + minute; }

        int count(String apiName, long minute) {
            AtomicInteger bucket = buckets.get(buildKey(apiName, minute));
            return bucket == null ? 0 : bucket.get();
        }

        /** 清理过期桶：只保留最近 keepMinutes 分钟。线上就是一个每分钟跑一次的定时任务在调它 */
        int cleanExpired(int keepMinutes) {
            long oldestMinuteToKeep = clock.nowMinute() - keepMinutes + 1;
            int removedCount = 0;
            for (String key : new java.util.ArrayList<>(buckets.keySet())) {   // 先拷一份 key 再删，避免边遍历边改
                if (parseMinute(key) < oldestMinuteToKeep) {
                    buckets.remove(key);
                    removedCount++;
                }
            }
            return removedCount;
        }

        int bucketCount() { return buckets.size(); }

        /** 把当前所有桶按分钟号排好序，方便打印查看 */
        Map<Long, Integer> snapshotByMinute() {
            Map<Long, Integer> sorted = new TreeMap<>();
            buckets.forEach((key, value) -> sorted.put(parseMinute(key), value.get()));
            return sorted;
        }

        /** 从 key 里把分钟号抠出来（key 的最后一段就是分钟号） */
        static long parseMinute(String key) { return Long.parseLong(key.substring(key.lastIndexOf(':') + 1)); }
    }

    /** 攒批上报器：请求进来只动本地内存，攒够一段时间再打包发给 Redis，几万次调用压成一两次网络请求 */
    static class BatchReporter {
        final ConcurrentHashMap<String, AtomicInteger> stagingArea = new ConcurrentHashMap<>();
        final Jedis jedis;
        final Clock clock;
        final AtomicInteger networkCallCount = new AtomicInteger();

        BatchReporter(Jedis jedis, Clock clock) { this.jedis = jedis; this.clock = clock; }
        /** 业务线程调这个：只在本地内存 +1，不走网络 */
        void record(String apiName) {
            stagingArea.computeIfAbsent(MinuteCounter.buildKey(apiName, clock.nowMinute()),
                    key -> new AtomicInteger()).incrementAndGet();
        }

        /** 后台线程每 10 秒调一次：把攒下的量一次性推给 Redis */
        void flush() {
            stagingArea.forEach((key, bucket) -> {
                // getAndSet(0) = 把桶里的数全部倒出来并清空，倒的过程中新来的请求不会丢
                int accumulated = bucket.getAndSet(0);
                if (accumulated > 0) {
                    jedis.incrBy(key, accumulated);   // INCRBY：一次网络请求顶 accumulated 次
                    jedis.expire(key, 300);
                    networkCallCount.incrementAndGet();
                }
            });
        }
    }

    /** 用 synchronized 加锁的计数器，只在实验五里作为反面对照 */
    static class LockedCounter {
        int count = 0;
        synchronized void increase() { count++; }
        synchronized int get() { return count; }
    }

    // ========== 打印和跑并发的小工具 ==========

    /** 开 threadCount 个线程同时跑同一段活儿，等全部跑完才返回，返回耗时毫秒 */
    static long runConcurrently(int threadCount, Runnable job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);   // 发令枪：让所有线程尽量同时起跑
        long startNanos = System.nanoTime();
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    job.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startGun.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);       // shutdown + awaitTermination 就够了，等所有任务跑完
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static String tick(boolean ok) {
        return ok ? "[正好对上]" : "[对不上!]";
    }

    static void printTitle(String title) {
        System.out.println("\n==================================================");
        System.out.println(title);
        System.out.println("==================================================");
    }
}
