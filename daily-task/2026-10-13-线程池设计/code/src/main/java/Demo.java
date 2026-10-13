/*
 * 这个程序在干嘛？
 *   演示「设计一个线程池」的核心机制。任务提交进来不是无脑开线程，而是按四步走：
 *     ① 先用核心线程（正式工）  ② 核心满了进队列排队
 *     ③ 队列满了才开非核心线程（临时工）  ④ 连最大线程数都到顶了，交给拒绝策略。
 *
 *   两套实现对照着看：
 *     - MiniThreadPool：手写的迷你线程池，把上面四步摊开，每做一步都打印出来；
 *     - JDK 原生 ThreadPoolExecutor：配一模一样的参数，验证行为与手写版一致，
 *       再演示四种真实拒绝策略，以及 Executors 工厂方法「无界队列」的隐患。
 *
 * 演示四件事：
 *   1) 手写线程池的四步扩容流程
 *   2) 原生 ThreadPoolExecutor 用同样参数，结果一致
 *   3) 四种拒绝策略分别是什么下场
 *   4) Executors.newFixedThreadPool 的队列没有上限，为什么线上不敢用
 *
 * 前置条件：纯 JDK，不需要任何中间件，直接跑：
 *   mvn -q compile exec:java
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) throws Exception {
        step1MiniPool();
        step2NativePool();
        step3RejectPolicies();
        step4UnboundedQueueRisk();
    }

    /** 第 1 步：手写迷你线程池，看 8 个任务进来会怎么走 */
    private static void step1MiniPool() throws Exception {
        System.out.println("=== 第 1 步：手写线程池 —— 8 个任务进来，会怎么分流 ===");
        // 核心 2 个「正式工」，最多再招 2 个「临时工」，排队区只能站 2 个
        MiniThreadPool pool = new MiniThreadPool(2, 4, 2, 800);
        CountDownLatch finished = new CountDownLatch(6);   // 预计只有 6 个任务能被接住

        for (int i = 1; i <= 8; i++) {
            final int taskId = i;
            try {
                pool.execute(() -> {
                    System.out.println("      [干活] 任务 " + taskId + " 由 " + Thread.currentThread().getName() + " 处理");
                    sleep(600);
                    finished.countDown();
                });
            } catch (RejectedExecutionException e) {
                System.out.println("      [结果] 任务 " + taskId + " 被拒绝：" + e.getMessage());
            }
            sleep(30);   // 让上一步的决策先打印出来，输出顺序才看得清
        }
        finished.await(5, TimeUnit.SECONDS);
        System.out.println("    最终线程总数 = " + pool.workerCount() + "（上限 " + pool.maxSize + "）");
        System.out.println();
    }

    /**
     * 手写迷你线程池：把「核心线程 -> 队列 -> 扩容 -> 拒绝」四步写清楚。
     * 生产环境请直接用 java.util.concurrent.ThreadPoolExecutor，这里只是把机制摊开给人看。
     */
    static class MiniThreadPool {
        final int coreSize;                             // 正式工数量，一直活着
        final int maxSize;                              // 正式工 + 临时工的人数上限
        private final BlockingQueue<Runnable> queue;    // 有界排队区
        private final AtomicInteger threadCount = new AtomicInteger();   // 已经开出去的线程数
        private final long keepAliveMillis;             // 临时工闲多久就下班

        MiniThreadPool(int coreSize, int maxSize, int queueCapacity, long keepAliveMillis) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.keepAliveMillis = keepAliveMillis;
        }

        int workerCount() {
            return threadCount.get();
        }

        void execute(Runnable task) {
            // 第 1 步：正式工还没坐满，直接派一个正式工接活
            if (threadCount.get() < coreSize) {
                addWorker(task, "正式工");
                return;
            }
            // 第 2 步：正式工都忙，先看排队区还有没有位置
            if (queue.offer(task)) {
                System.out.println("      [决策] 核心线程已满 -> 进队列排队（队列现在 " + queue.size() + " 个）");
                return;
            }
            // 第 3 步：排队区也满了，才临时招人顶上
            if (threadCount.get() < maxSize) {
                addWorker(task, "临时工");
                return;
            }
            // 第 4 步：人数到上限、队伍也满，只能拒绝（等价于 ThreadPoolExecutor 的 AbortPolicy）
            System.out.println("      [决策] 线程数已到上限 " + maxSize + " 且队列已满 -> 拒绝");
            throw new RejectedExecutionException("线程池已满");
        }

        private void addWorker(Runnable firstTask, String role) {
            int index = threadCount.incrementAndGet();
            Thread worker = new Thread(() -> {
                runTask(firstTask);                 // 先把手上这个任务干完
                while (true) {                      // 干完再去排队区领活
                    Runnable next;
                    try {
                        next = queue.poll(keepAliveMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (next == null) {
                        if (threadCount.get() > coreSize) {
                            threadCount.decrementAndGet();   // 我是临时工，闲太久就下班
                            return;
                        }
                        continue;                            // 我是正式工，继续守着
                    }
                    runTask(next);
                }
            }, role + "-" + index);
            System.out.println("      [决策] 开一个" + role + "（" + worker.getName() + "）接活");
            worker.setDaemon(true);   // 守护线程，主线程跑完不会卡住进程
            worker.start();
        }

        private static void runTask(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.out.println("      任务执行出错：" + e);
            }
        }
    }

    /** 第 2 步：换成正儿八经的 ThreadPoolExecutor，参数一样，行为应该也一样 */
    private static void step2NativePool() throws Exception {
        System.out.println("=== 第 2 步：JDK 原生 ThreadPoolExecutor —— 同样参数，同样结果 ===");
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),        // 有界队列，容量 2
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        int accepted = 0;
        int rejected = 0;
        for (int i = 1; i <= 8; i++) {
            try {
                pool.execute(() -> sleep(400));
                accepted++;
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        System.out.println("    提交 8 个任务 -> 接住 " + accepted + " 个，拒绝 " + rejected + " 个");
        System.out.println("    此刻 线程数=" + pool.getPoolSize() + "，排队中=" + pool.getQueue().size());
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println();
    }

    /** 第 3 步：线程满了、队列也满了，四种拒绝策略分别是什么下场 */
    private static void step3RejectPolicies() throws Exception {
        System.out.println("=== 第 3 步：四种拒绝策略（核心 1 / 最大 1 / 队列 1，连投 3 个任务）===");
        demoPolicy("AbortPolicy（抛异常，默认）", new ThreadPoolExecutor.AbortPolicy());
        demoPolicy("DiscardPolicy（悄悄丢掉新任务）", new ThreadPoolExecutor.DiscardPolicy());
        demoPolicy("CallerRunsPolicy（调用者自己跑）", new ThreadPoolExecutor.CallerRunsPolicy());
        demoPolicy("DiscardOldestPolicy（丢掉队头最老的）", new ThreadPoolExecutor.DiscardOldestPolicy());
        System.out.println();
    }

    private static void demoPolicy(String name, RejectedExecutionHandler policy) throws Exception {
        System.out.println("  · " + name);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), Executors.defaultThreadFactory(), policy);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= 3; i++) {
            final String taskName = "任务" + i;
            try {
                pool.execute(() -> {
                    events.add(taskName + " 由 " + Thread.currentThread().getName() + " 执行");
                    sleep(500);
                });
            } catch (RejectedExecutionException e) {
                events.add(taskName + " 被拒绝");
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("      实际发生：" + events);
    }

    /** 第 4 步：为什么线上不建议用 Executors 工厂方法 */
    private static void step4UnboundedQueueRisk() {
        System.out.println("=== 第 4 步：Executors 工厂方法的坑 —— 队列没有上限 ===");
        ExecutorService fixed = Executors.newFixedThreadPool(1);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) fixed;
        System.out.println("    newFixedThreadPool 用的队列：" + pool.getQueue().getClass().getSimpleName());
        System.out.println("    它的剩余容量：" + pool.getQueue().remainingCapacity()
                + " -> 任务可以无限堆，堆到内存爆掉就是 OOM");
        System.out.println("    同理 newCachedThreadPool 的线程数上限是 " + Integer.MAX_VALUE + "，线程也能无限开");
        fixed.shutdown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
