/**
 * 这个程序演示一个很常见的场景：第三方上游（比如支付平台）不断回调我们的接口，
 * 我们该怎么「先收下、慢慢处理」，也就是题目问的——要不要用 MQ？不用 MQ 还能怎么办？
 *
 * 程序里用三种最朴素的方式各演一遍，方便对比：
 *   demoThreadPoolQueue()  方案一：线程池 + 内存队列（最轻，进程一挂就丢）
 *   demoDbPolling()        方案二：数据库轮询（最稳，但慢一拍）
 *   demoRedisList()        方案三：Redis list 当轻量队列（折中）
 *
 * 三个方案都会撞上同一个坑：上游会重复推消息。所以每个方案里都带了「幂等」处理——
 * 同一个订单号处理多次，只有第一次算数。这就像门卫只认第一张票，后面重样的票直接撕掉，
 * 不然就会出现重复扣款、重复发货这种事故。
 *
 * 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    /**
     * 幂等账本：记录每个订单号被处理了几次。
     * key = 订单号，value = 处理次数。用 ConcurrentHashMap 是因为多个工人线程会同时来记账。
     */
    private static final Map<String, AtomicInteger> processedOrders = new ConcurrentHashMap<>();

    /** 上游一共推了多少次消息（含重复的）。 */
    private static final AtomicInteger totalReceived = new AtomicInteger(0);

    /** 真正干了活的次数。它和 totalReceived 的差值，就是被幂等挡掉的重复消息数。 */
    private static final AtomicInteger realWorkCount = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("第三方上游接口异步处理：到底要不要用 MQ？这里用三种朴素方式各演示一遍。");

        demoThreadPoolQueue();
        demoDbPolling();
        demoRedisList();

        System.out.println("\n=== 小结 ===");
        System.out.println("上游共推来 " + totalReceived.get() + " 次消息，真正干活 " + realWorkCount.get()
                + " 次，剩下 " + (totalReceived.get() - realWorkCount.get()) + " 次都是重复消息，被幂等挡掉了。");
        System.out.println("选型口诀：");
        System.out.println("  小流量、单机内部解耦      → 线程池 + 队列（最省事）");
        System.out.println("  怕丢、能容忍几十秒延迟    → 数据库轮询（最稳）");
        System.out.println("  想快又要轻                → Redis list（折中）");
        System.out.println("  要跨系统解耦 + 削峰 + 不丢 → 上正式 MQ，但记得配好监控和堆积告警");
    }

    /**
     * 方案一：线程池 + 内存队列。
     * 思路：请求进来先丢进线程池的队列，主线程立刻往下走（这就是「异步」），
     *      后台的工人线程有空了再去处理。
     */
    private static void demoThreadPoolQueue() {
        System.out.println("\n=== 方案一：线程池 + 内存队列（最轻量，进程一挂数据就丢） ===");
        System.out.println("思路：回调进来先丢进队列，立刻给上游回「收到了」，后面由工人线程慢慢处理。");

        // 3 个固定工人；任务先排队，工人有空就来取。这个队列就是「内存里的收件筐」。
        ExecutorService workers = Executors.newFixedThreadPool(3);

        // 模拟上游回调了 6 次，其中订单 T-1001 被重复推了 3 次（上游重试很常见）
        List<String> callbacks = List.of("T-1001", "T-1002", "T-1001", "T-1003", "T-1001", "T-1004");

        for (String orderNo : callbacks) {
            // 用 execute 提交任务，只是「入队」，主线程不等它做完就继续往下走 —— 这就是异步
            workers.execute(() -> handlePaymentCallback(orderNo, "线程池队列"));
        }

        // 等所有任务跑完（真实系统里线程池是常驻的，这里为了演示能正常结束才主动关）
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("  上游 6 次回调已经全部收下，主流程没被拖住。");
        System.out.println("  注意：队列在内存里，服务重启或崩溃，筐里没处理完的活儿就没了。");
    }

    /**
     * 方案二：数据库轮询。
     * 思路：回调来了先往任务表插一行（相当于「记账」），然后交给定时任务扫表处理。
     *      数据落了盘，重启也不怕丢，代价是慢一拍。
     */
    private static void demoDbPolling() {
        System.out.println("\n=== 方案二：数据库轮询（最稳，但慢一拍） ===");
        System.out.println("思路：回调来了先往 task 表插一行，定时任务再扫表处理。");

        // 用一个内存 List 假装是数据库里的任务表
        List<TaskRow> taskTable = new ArrayList<>();
        taskTable.add(new TaskRow("D-2001", 0));
        taskTable.add(new TaskRow("D-2002", 0));
        taskTable.add(new TaskRow("D-2001", 0)); // 上游重试，又插进来一条重复的

        System.out.println("  回调到达时只往表里插一行（相当于记账），立刻返回，不阻塞上游。");
        System.out.println("  下面手动模拟定时任务扫 1 轮表：");

        for (int i = 0; i < taskTable.size(); i++) {
            TaskRow row = taskTable.get(i);
            if (row.status() == 0) {
                handlePaymentCallback(row.orderNo(), "数据库轮询");
                // 不管是真处理了还是重复消息，都标记成已完成，
                // 否则定时任务会一遍遍扫到同一条，白干活。
                taskTable.set(i, new TaskRow(row.orderNo(), 1));
            }
        }

        System.out.println("  扫描结束，表里还剩 " + taskTable.size() + " 行，状态都变成已完成。");
        System.out.println("  注意：实时性差，得等下一个扫描周期；表大了扫描也会变慢。");
    }

    /**
     * 方案三：用 Redis 的 list 当轻量队列。
     * 思路：生产端 lpush 塞进去，消费端 blpop 阻塞取出来。
     *      比数据库快，又比正式 MQ 轻，属于折中方案。
     */
    private static void demoRedisList() {
        System.out.println("\n=== 方案三：Redis list 当轻量队列（折中方案） ===");
        System.out.println("思路：用 lpush 塞、blpop 阻塞取；比数据库快，又比正式 MQ 轻。");

        // 用 LinkedBlockingDeque 模拟 Redis 的 list：一头塞、另一头取
        LinkedBlockingDeque<String> redisList = new LinkedBlockingDeque<>();

        // 模拟上游回调：塞 4 条，其中 R-3001 重复了一次
        for (String orderNo : List.of("R-3001", "R-3002", "R-3001", "R-3003")) {
            redisList.addFirst(orderNo); // 对应 Redis 的 lpush
        }
        System.out.println("  塞完 " + redisList.size() + " 条，下面开始 blpop（阻塞取）：");

        while (!redisList.isEmpty()) {
            // pollLast 对应 Redis 的 blpop：队列空时会阻塞等着，来一条取一条
            String orderNo = redisList.pollLast();
            handlePaymentCallback(orderNo, "Redis队列");
        }

        System.out.println("  注意：Redis 自己也会挂，得配主从或哨兵；数据可靠性比不上专业 MQ。");
    }

    /**
     * 真正处理一条「支付回调」的业务方法。
     *
     * 关键点：先看幂等账本——处理过就直接返回，避免重复扣款。
     * 这里用 AtomicInteger 的 incrementAndGet() 当「抢票」：
     *   只有第一个把计数从 0 加到 1 的调用，才算抢到了处理权。
     *
     * @param orderNo 订单号
     * @param source  是谁在处理（线程池队列 / 数据库轮询 / Redis队列），只用于打印区分
     * @return true 表示这次真的干了活；false 表示是重复消息，被幂等挡住了
     */
    private static boolean handlePaymentCallback(String orderNo, String source) {
        totalReceived.incrementAndGet();

        // computeIfAbsent：账本里没有这个订单号，就先记上 0 次
        AtomicInteger times = processedOrders.computeIfAbsent(orderNo, key -> new AtomicInteger(0));

        if (times.incrementAndGet() > 1) {
            System.out.println("  [" + source + "] 订单 " + orderNo + " 是重复消息，直接跳过（幂等生效）");
            return false;
        }

        realWorkCount.incrementAndGet();
        System.out.println("  [" + source + "] 处理订单 " + orderNo + "：核对金额、更新订单状态 ... 完成");
        return true;
    }

    /**
     * 任务表里的一行。
     * status：0 表示待处理，1 表示已完成。
     */
    private record TaskRow(String orderNo, int status) {
    }
}
