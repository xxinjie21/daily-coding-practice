import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 这个程序演示原题「两百万个生产者发消息、只有一个消费者，锁怎么设计才高效」。
 *
 * 原题结论：锁不是重点——两百万生产者抢同一把锁，系统早就崩了；关键是让锁压根不需要出现：
 *   1) 生产者按 ID 分片，各写各的队列（对应 Kafka 的 Partition / RocketMQ 的 MessageQueue）；
 *   2) 队列内部用无锁结构（ConcurrentLinkedQueue，基于 CAS），多个生产者并发写不互相阻塞；
 *   3) 一个消费者轮询所有分片；消费跟不上就评估扩容消费者。
 *
 * 程序把同一批 200 万条消息用三种方式收发，分别计量两段时间：
 *   生产耗时：32 个生产者把消息全部写完（隔离看「锁的代价」）；
 *   总耗时  ：消费者也全部收完（含每条消息的固定处理开销，看「消费上限」）。
 *   方案一：全局一把 ReentrantLock + 一个共享队列（反面教材）
 *   方案二：分 64 片 + CAS 无锁队列，1 个消费者
 *   方案三：分 64 片 + CAS 无锁队列，8 个消费者并行消费
 * 三种方案收到的消息是同一批（生产者 ID 合计完全相同），差别只在收发结构。
 *
 * 前置条件：纯 JDK 并发程序，不需要任何中间件，直接运行即可。
 */
public class Demo {

    /** 一次收发计时：生产耗时 / 总耗时（毫秒）/ 消费者收到的生产者 ID 合计（校验用） */
    record Timing(long produceMs, long totalMs, long checksum) {}

    /** 题目里的「两百万个生产者」，每人发 1 条消息 */
    static final int PRODUCERS = 2_000_000;
    /** 用 32 个线程代打，模拟两百万个生产者的发送动作（真实项目里是 200 万个连接或进程） */
    static final int PRODUCER_THREADS = 32;
    /** 分片队列数，对应 Kafka 的 Partition 数量 */
    static final int SHARDS = 64;
    /** 热身消息数：先小跑一遍暖 JIT，避免第一轮的编译耗时污染正式计时 */
    static final int WARMUP = 200_000;
    /** 消费者每条消息的模拟处理量：FNV 滚动哈希 300 轮（约几百纳秒），相当于真实项目里的序列化/校验 */
    static final int WORK_ITERATIONS = 300;

    public static void main(String[] args) throws Exception {
        System.out.println("场景：" + PRODUCERS + " 个生产者各发 1 条消息，消费者带固定处理开销地全部收完");
        System.out.println("分片规则：producerId % " + SHARDS + "，例如 1234567 落第 "
                + (1234567 % SHARDS) + " 片，1999999 落第 " + (1999999 % SHARDS) + " 片");
        System.out.println("每种方案先跑 " + WARMUP + " 条热身（暖 JIT），再正式计量\n");

        warmup();

        Timing one = globalLockRun(PRODUCERS);
        System.out.println("方案一 全局锁 + 共享队列，" + PRODUCER_THREADS + " 个生产者和 1 个消费者抢同一把锁:");
        System.out.println("      生产 " + one.produceMs() + " ms，总耗时 " + one.totalMs() + " ms，"
                + "消费者校验和 " + one.checksum());

        Timing two = shardedRun(PRODUCERS, 1);
        System.out.println("方案二 按 ID 分 " + SHARDS + " 片 + CAS 无锁队列，1 个消费者轮询:");
        System.out.println("      生产 " + two.produceMs() + " ms，总耗时 " + two.totalMs() + " ms，"
                + "消费者校验和 " + two.checksum());

        Timing three = shardedRun(PRODUCERS, 8);
        System.out.println("方案三 同样分 " + SHARDS + " 片，8 个消费者各认领 8 片并行消费:");
        System.out.println("      生产 " + three.produceMs() + " ms，总耗时 " + three.totalMs() + " ms，"
                + "消费者校验和 " + three.checksum());

        System.out.println();
        System.out.println("三个方案的校验和完全相同，说明收到的确实是同一批 200 万条消息，差别只在收发结构。");
        System.out.println("结论一：生产侧，全局锁把 32 个生产者 + 1 个消费者全堵在同一把锁上，分片 + 无锁队列明显更快；");
        System.out.println("结论二：消费侧，一个消费者的处理速度是硬上限，消息越重越先撞到它；");
        System.out.println("结论三：真撞上了就扩容消费者——分片正好让并行消费成为可能（方案三总耗时已接近生产耗时）。");
    }

    /** 热身：三种方案各小跑一遍，让 JIT 先把热点代码编译好 */
    static void warmup() throws InterruptedException {
        globalLockRun(WARMUP);
        shardedRun(WARMUP, 1);
        shardedRun(WARMUP, 8);
    }

    /** 方案一（反面教材）：一把全局锁 + 一个共享队列，生产者消费者全抢这把锁 */
    static Timing globalLockRun(int total) throws InterruptedException {
        Queue<Long> sharedQueue = new LinkedList<>();
        ReentrantLock lock = new ReentrantLock();
        AtomicLong consumed = new AtomicLong();
        long[] checksumBox = new long[1];
        int perThread = total / PRODUCER_THREADS;
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_THREADS);

        long start = System.nanoTime();
        for (int t = 0; t < PRODUCER_THREADS; t++) {
            final long base = (long) t * perThread;
            Thread producer = new Thread(() -> {
                for (long producerId = base; producerId < base + perThread; producerId++) {
                    lock.lock();
                    try {
                        sharedQueue.add(producerId);
                    } finally {
                        lock.unlock();
                    }
                }
                producersDone.countDown();
            }, "producer-" + t);
            producer.start();
        }

        Thread consumer = new Thread(() -> {
            long[] sink = new long[2];
            while (consumed.get() < total) {
                lock.lock();
                boolean empty;
                try {
                    Long msg = sharedQueue.poll();
                    empty = msg == null;
                    if (msg != null) {
                        handle(msg, sink);
                        consumed.incrementAndGet();
                    }
                } finally {
                    lock.unlock();
                }
                if (empty) {
                    LockSupport.parkNanos(200_000); // 暂时没货，先让出 CPU
                }
            }
            checksumBox[0] = sink[0];
        }, "global-consumer");
        consumer.start();

        producersDone.await(); // 生产全部写完
        long produceMs = elapsedMs(start);
        consumer.join(); // 消费也全部收完
        return new Timing(produceMs, elapsedMs(start), checksumBox[0]);
    }

    /** 方案二/三：生产者按 ID 取模分片，每片一个无锁队列；consumers 个消费者各认领几个分片 */
    static Timing shardedRun(int total, int consumers) throws InterruptedException {
        List<ConcurrentLinkedQueue<Long>> shards = new ArrayList<>();
        for (int i = 0; i < SHARDS; i++) {
            shards.add(new ConcurrentLinkedQueue<>());
        }
        AtomicLong[] consumedByConsumer = new AtomicLong[consumers];
        long[][] sinks = new long[consumers][];
        for (int c = 0; c < consumers; c++) {
            consumedByConsumer[c] = new AtomicLong();
            sinks[c] = new long[2];
        }
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_THREADS);
        CountDownLatch consumersDone = new CountDownLatch(consumers);
        int perThread = total / PRODUCER_THREADS;

        long start = System.nanoTime();
        for (int t = 0; t < PRODUCER_THREADS; t++) {
            final long base = (long) t * perThread;
            Thread producer = new Thread(() -> {
                for (long producerId = base; producerId < base + perThread; producerId++) {
                    // 按生产者 ID 取模选队列：一行代码，不加任何锁
                    shards.get((int) (producerId % SHARDS)).add(producerId);
                }
                producersDone.countDown();
            }, "producer-" + t);
            producer.start();
        }

        for (int c = 0; c < consumers; c++) {
            final int id = c;
            long[] mySink = sinks[id];
            AtomicLong mine = consumedByConsumer[id]; // 每个消费者一个私有计数器，避免互相抢
            Thread consumer = new Thread(() -> {
                while (sum(consumedByConsumer) < total) {
                    boolean got = false;
                    for (int s = id; s < SHARDS; s += consumers) { // 每个消费者认领固定的几个分片
                        Long msg = shards.get(s).poll();
                        if (msg != null) {
                            handle(msg, mySink);
                            mine.incrementAndGet();
                            got = true;
                        }
                    }
                    if (!got) {
                        LockSupport.parkNanos(200_000);
                    }
                }
                consumersDone.countDown();
            }, "consumer-" + id);
            consumer.start();
        }

        producersDone.await();
        long produceMs = elapsedMs(start);
        consumersDone.await();
        long checksum = 0;
        for (long[] sink : sinks) {
            checksum += sink[0];
        }
        return new Timing(produceMs, elapsedMs(start), checksum);
    }

    /** 消费者拿到一条消息后的固定处理开销，三种方案完全一致 */
    static void handle(long producerId, long[] sink) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < WORK_ITERATIONS; i++) {
            h = (h ^ producerId) * 0x100000001b3L;
        }
        sink[0] += producerId; // 与消费顺序无关，用来校验三个方案收到的是同一批消息
        sink[1] ^= h;          // 让上面的计算真正发生，不被 JIT 当死代码删掉
    }

    /** 把各消费者自己的计数加起来看总量（读比写轻得多，不会成为瓶颈） */
    static long sum(AtomicLong[] counters) {
        long total = 0;
        for (AtomicLong counter : counters) {
            total += counter.get();
        }
        return total;
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
