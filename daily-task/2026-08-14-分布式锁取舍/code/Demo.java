// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
// 面试官问：「你用了分布式锁，并发不就降了吗？」
// 我们用 Java 并发把这句话拆开演示：锁确实会牺牲一部分并发，但那是「为正确性买单」，
// 而且我们可以通过「缩小锁粒度」和「用无锁原子操作」把并发抢回来。
//
// 程序对比三种「扣库存」写法（秒杀场景的精华）：
//   方案1 全局锁   ：所有线程抢同一把锁，完全串行 —— 最慢，但绝不会超卖
//   方案2 分段锁   ：按商品 ID 分桶，不同桶各用各的锁，只有同桶才排队 —— 并发更高
//   方案3 无锁原子 ：用 CAS（compareAndSet）直接扣，压根不用锁 —— 最快
// 最后验证：不管哪种写法，卖出去的数量都「正好等于」扣掉的数量，绝不会超卖。

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

public class Demo {

    // 模拟的线程数（相当于同时来抢的 50 个用户）
    static final int THREADS = 50;
    // 每个线程尝试购买的次数（总操作量 = 50 * 20000 = 100 万次，足够让锁竞争显现出来）
    static final int OPS_PER_THREAD = 20000;
    // 商品数量（库存按商品分桶，这里用 8 个商品）
    static final int PRODUCTS = 8;
    // 分段锁的桶数（要 >= 商品数，保证每个商品落在不同桶，才能体现「不同桶并行」）
    static final int SEGMENTS = 16;

    // 模拟「扣库存时要顺手做的工作」，比如更新缓存、写一条流水。这正是锁要保护的那段"真活"。
    // 给临界区一点真实工作量，串行化的代价才会显现出来；否则锁本身的切换开销会盖过一切，测不准。
    static volatile long workSink;   // 用 volatile 接住计算结果，防止 JIT 把这段循环整个优化掉
    static long simulateWork() {
        long x = 0;
        for (int i = 0; i < 3000; i++) {
            x += (i * 3L + 1);
        }
        workSink += x;
        return x;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== 正确性演示：限量库存，证明「绝不超卖」 ==========");
        correctnessDemo();

        System.out.println();
        System.out.println("========== 性能演示：大库存，对比三种方案耗时 ==========");
        performanceDemo();
    }

    // -------------------------------------------------------------------------
    // 正确性演示：库存只有一点点，让大量请求来抢，看会不会卖超
    // -------------------------------------------------------------------------
    static void correctnessDemo() throws Exception {
        int stockPerProduct = 100;          // 每个商品只有 100 件，总共 800 件
        long totalStock = (long) stockPerProduct * PRODUCTS;

        // 用「全局锁」版本抢一遍
        GlobalLockDeduct global = new GlobalLockDeduct(stockPerProduct);
        int soldGlobal = runAllThreads(global);
        long deductedGlobal = totalStock - global.remainingSum();  // 扣掉的数量 = 总库存 - 剩下的
        System.out.println("[全局锁]   卖出=" + soldGlobal
                + "，实际扣减=" + deductedGlobal
                + "，剩余库存=" + global.remainingSum()
                + " -> " + (soldGlobal == deductedGlobal && deductedGlobal == totalStock ? "没超卖" : "超卖了"));

        // 用「无锁原子」版本抢一遍（证明不靠锁也能保正确）
        AtomicDeduct atomic = new AtomicDeduct(stockPerProduct);
        int soldAtomic = runAllThreads(atomic);
        long deductedAtomic = totalStock - atomic.remainingSum();
        System.out.println("[无锁原子] 卖出=" + soldAtomic
                + "，实际扣减=" + deductedAtomic
                + "，剩余库存=" + atomic.remainingSum()
                + " -> " + (soldAtomic == deductedAtomic && deductedAtomic == totalStock ? "没超卖" : "超卖了"));

        System.out.println("结论：两种写法卖出的件数都「一分不差」等于库存，说明锁/CAS 都守住了正确性。");
    }

    // -------------------------------------------------------------------------
    // 性能演示：给足库存让所有请求都能成功，纯粹比「锁竞争带来的耗时」
    // -------------------------------------------------------------------------
    static void performanceDemo() throws Exception {
        int bigStock = 500_000;            // 每个商品 50 万件，总 400 万件，保证请求基本都能成功

        // 先热身，让 JVM 把热点代码编译优化好，否则第一次测得偏慢、不公平
        warmUp(bigStock);

        long t1 = System.nanoTime();
        GlobalLockDeduct g = new GlobalLockDeduct(bigStock);
        int s1 = runAllThreads(g);
        long ms1 = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        SegmentLockDeduct seg = new SegmentLockDeduct(bigStock);
        int s2 = runAllThreads(seg);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;

        long t3 = System.nanoTime();
        AtomicDeduct a = new AtomicDeduct(bigStock);
        int s3 = runAllThreads(a);
        long ms3 = (System.nanoTime() - t3) / 1_000_000;

        // 按实测耗时动态标注"最慢/最快"，避免写死结论
        String label1 = label(ms1, ms1, ms2, ms3);
        String label2 = label(ms2, ms1, ms2, ms3);
        String label3 = label(ms3, ms1, ms2, ms3);

        System.out.println("各方案完成 " + s1 + " 次成功扣减的耗时：");
        System.out.println("  全局锁   ：" + ms1 + " ms " + label1 + "（50 个线程全挤一把锁）");
        System.out.println("  分段锁   ：" + ms2 + " ms " + label2 + "（不同商品各排各的队）");
        System.out.println("  无锁原子 ：" + ms3 + " ms " + label3 + "（压根不用排队）");
        System.out.println("结论：锁粒度越粗越慢；能原子就别锁，并发直接拉满。");
    }

    // 让每种方案各跑一小会儿，触发 JIT 编译，保证正式计时公平
    static void warmUp(int bigStock) throws Exception {
        runAllThreads(new AtomicDeduct(bigStock), 4, 2000);
        runAllThreads(new SegmentLockDeduct(bigStock), 4, 2000);
        runAllThreads(new GlobalLockDeduct(bigStock), 4, 2000);
    }

    // 根据三个方案的实测耗时，给当前这一个标上"最慢/最快/居中"
    static String label(long me, long a, long b, long c) {
        long max = Math.max(Math.max(a, b), c);
        long min = Math.min(Math.min(a, b), c);
        if (me == max) return "（最慢）";
        if (me == min) return "（最快）";
        return "（居中）";
    }

    // 启动 THREADS 个线程，每个线程循环 OPS_PER_THREAD 次随机挑一个商品尝试购买
    static int runAllThreads(Deduct deduct) throws Exception {
        return runAllThreads(deduct, THREADS, OPS_PER_THREAD);
    }

    static int runAllThreads(Deduct deduct, int threads, int ops) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);   // 发令枪：让所有线程同一刻起跑
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();                       // 等发令枪，避免先提交的线程提前开跑
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int k = 0; k < ops; k++) {
                        // 随机挑一个商品尝试买 1 件；买到返回 true，没库存返回 false
                        deduct.tryBuy(rnd.nextInt(PRODUCTS));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();        // 鸣枪
        done.await();             // 等所有人跑完
        pool.shutdown();
        return deduct.soldCount();
    }

    // ===================== 三种「扣库存」实现 =====================

    // 统一的扣减接口：尝试买 1 件，成功返回 true
    interface Deduct {
        boolean tryBuy(int productId);
        int soldCount();
        long remainingSum();   // 当前所有商品「还剩下的库存」之和，用来核对有没有超卖
    }

    // 方案1：全局锁 —— 所有线程抢同一把锁，完全串行
    // 就像全小区只有 1 个快递柜，谁取件都得等前一个人。
    static class GlobalLockDeduct implements Deduct {
        private final Object globalLock = new Object();   // 唯一的锁，所有人抢这一把
        private final int[] stock = new int[PRODUCTS];     // 每个商品的剩余库存
        private final AtomicInteger sold = new AtomicInteger(0);

        GlobalLockDeduct(int stockPerProduct) {
            Arrays.fill(stock, stockPerProduct);
        }

        @Override
        public boolean tryBuy(int productId) {
            // 锁住「全局」这把钥匙：同一时刻只有 1 个线程能进这个代码块
            synchronized (globalLock) {
                if (stock[productId] > 0) {     // 还有货才卖
                    stock[productId]--;         // 扣 1 件
                    simulateWork();             // 顺手做点"真活"（更新缓存/写流水），这段也被锁串行化了
                    sold.incrementAndGet();     // 卖出数 +1（用原子变量，避免计数本身也打架）
                    return true;
                }
                return false;                  // 没货了，本次购买失败
            }
        }

        @Override
        public long remainingSum() {
            long sum = 0;
            for (int s : stock) sum += s;
            return sum;
        }

        @Override
        public int soldCount() { return sold.get(); }
    }

    // 方案2：分段锁 —— 按商品 ID 分桶，不同桶各用各的锁
    // 像每栋楼各 1 个快递柜，1 栋楼取件不影响 2 栋楼，排队压力被分摊了。
    static class SegmentLockDeduct implements Deduct {
        private final Object[] segmentLocks = new Object[SEGMENTS];  // 16 把锁，按桶分配
        private final int[] stock = new int[PRODUCTS];
        private final AtomicInteger sold = new AtomicInteger(0);

        SegmentLockDeduct(int stockPerProduct) {
            Arrays.fill(stock, stockPerProduct);
            for (int i = 0; i < SEGMENTS; i++) segmentLocks[i] = new Object();
        }

        @Override
        public boolean tryBuy(int productId) {
            // 用「商品 ID % 桶数」选出这件商品对应的那把锁，而不是全局唯一锁
            Object myLock = segmentLocks[productId % SEGMENTS];
            synchronized (myLock) {             // 只和「买同一桶商品」的人排队，其他人并行
                if (stock[productId] > 0) {
                    stock[productId]--;
                    simulateWork();             // 同桶的人串行做"真活"，不同桶的并行
                    sold.incrementAndGet();
                    return true;
                }
                return false;
            }
        }

        @Override
        public long remainingSum() {
            long sum = 0;
            for (int s : stock) sum += s;
            return sum;
        }

        @Override
        public int soldCount() { return sold.get(); }
    }

    // 方案3：无锁原子 —— 用 CAS（比较并交换）直接扣，压根不用锁
    // 像快递员直接把件塞进你家信箱的一条动作，中间不会被别人插断。
    static class AtomicDeduct implements Deduct {
        // 每个商品一个 AtomicInteger，decrementAndGet / compareAndSet 都是 CPU 级别的原子操作
        private final AtomicInteger[] stock = new AtomicInteger[PRODUCTS];
        private final AtomicInteger sold = new AtomicInteger(0);

        AtomicDeduct(int stockPerProduct) {
            for (int i = 0; i < PRODUCTS; i++) stock[i] = new AtomicInteger(stockPerProduct);
        }

        @Override
        public boolean tryBuy(int productId) {
            AtomicInteger s = stock[productId];
            int remaining;
            // CAS 自旋：读出当前值，>0 才尝试把它减 1；如果这期间被别人改了，就重读再试
            // 整个过程没有「加锁—等待」，多个线程可以同时推进，谁抢到谁减
            do {
                remaining = s.get();
                if (remaining <= 0) return false;   // 没货直接失败，不用抢
            } while (!s.compareAndSet(remaining, remaining - 1));
            simulateWork();             // "真活"在锁外并行做，不被任何锁串行化 —— 所以最快
            sold.incrementAndGet();
            return true;
        }

        @Override
        public long remainingSum() {
            long sum = 0;
            for (AtomicInteger s : stock) sum += s.get();
            return sum;
        }

        @Override
        public int soldCount() { return sold.get(); }
    }
}
