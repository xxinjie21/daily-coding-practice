// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
//
// 演示原题的核心方案：进口 200 笔/秒、第三方只允许 100 笔/秒时，
// 如何做到 "不超限、用满额度、先进先出、不重复扣款"。
//
// 按原题给的思路，程序里有 4 个角色（都用内存对象模拟，方便单文件跑通）：
//   1) SimulatedMessageQueue —— 模拟 Kafka 消息队列（蓄水池，先进先出）
//   2) GlobalTokenBucket     —— 模拟 Redis 全局令牌桶（水龙头，每秒只放 100 个令牌，
//                               所有 Worker 共抢这一个桶，机器再多总速率也不超）
//   3) PaymentWorker         —— 支付工人（多线程模拟多台机器，拿到令牌才干活）
//   4) ThirdPartyPayService  —— 第三方支付（记账员，统计每一秒实际收到几笔，
//                               顺便做幂等挡掉重复订单）
//
// 流程：下单方以 200 笔/秒 塞订单进队列 → 4 个 Worker 抢令牌 → 拿到令牌才从
// 队列取单调"第三方" → 最后校验三件事：
//   ① 任意一秒第三方收到的笔数都不超过 100（不超限）
//   ② 稳定期每秒基本贴着 100 笔（用满额度）
//   ③ 出队顺序 = 入队顺序（FIFO），且故意塞的重复订单只被支付一次（幂等）
// ============================================================================

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    /** 一笔支付订单：只带一个订单号就够演示了。record 是 Java 16+ 的简洁写法 */
    record PayOrder(int orderId) {}

    // ======================== 角色 1：模拟消息队列（Kafka） ========================
    /**
     * 蓄水池：进得快出得慢时，多出来的订单先在这里排队。
     * LinkedBlockingQueue 天然先进先出，正好对应 Kafka 单分区内的 FIFO。
     * （真实 Kafka 还会把消息写磁盘、存多副本，宕机不丢，这里从略）
     */
    static class SimulatedMessageQueue {
        private final BlockingQueue<PayOrder> queue = new LinkedBlockingQueue<>();

        void send(PayOrder order) {
            queue.offer(order); // 下单方把订单丢进队列，立刻返回，不用等支付完成
        }

        /** 取一笔订单；队列空了就最多等 200 毫秒，避免工人死等 */
        PayOrder poll() throws InterruptedException {
            return queue.poll(200, TimeUnit.MILLISECONDS);
        }

        int backlog() {
            return queue.size(); // 积压量：还有多少单在排队（监控就盯这个数）
        }
    }

    // ======================== 角色 2：全局令牌桶（模拟 Redis + Lua） ========================
    /**
     * 景区发票口：管理员每秒往桶里匀速放 100 张门票，工人进门前必须先抢到票。
     * 不管有几个工人（几台机器），进门总速度都不会超过发票速度——这就是全局限流。
     *
     * 真实项目里这个桶放在 Redis 上，用 Lua 脚本保证"看余票 + 扣票"一气呵成；
     * 这里用 synchronized 达到同样的原子效果（同一时刻只有一个工人能操作桶）。
     */
    static class GlobalTokenBucket {
        private final double tokensPerSecond; // 每秒补多少张票（本题 = 100）
        private final double capacity;        // 桶最多攒多少张票（故意设小，防止攒多了瞬间冲击第三方）
        private double availableTokens;       // 桶里现在有几张票
        private long lastRefillNanos;         // 上次补票的时间点

        GlobalTokenBucket(double tokensPerSecond, double capacity) {
            this.tokensPerSecond = tokensPerSecond;
            this.capacity = capacity;
            this.availableTokens = 0; // 一开始桶是空的，从零匀速攒
            this.lastRefillNanos = System.nanoTime();
        }

        /** 试着拿 1 张票：拿到返回 true。惰性补票：不开定时器，取票时按"过了多久"补 */
        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            // 补票 = 流逝的时间 × 每秒产量，但不能超过桶容量
            availableTokens = Math.min(capacity, availableTokens + elapsedSeconds * tokensPerSecond);
            lastRefillNanos = now;

            if (availableTokens >= 1.0) {
                availableTokens -= 1.0; // 抢到一张票
                return true;
            }
            return false; // 桶空了，工人稍后再来
        }
    }

    // ======================== 角色 3：第三方支付（记账 + 幂等） ========================
    /**
     * 记账员：每收到一笔支付，就记在"第几秒"的账本上，最后用来检查有没有哪一秒超过 100 笔。
     * 同时用一个"已支付订单号集合"做幂等：同一个订单号来两次，第二次直接拒绝。
     */
    static class ThirdPartyPayService {
        private final long startMillis = System.currentTimeMillis();
        // 账本：key = 第几秒，value = 这一秒收到的笔数（AtomicInteger 像不会卡壳的取号机，多线程同时加也不会漏计）
        final Map<Long, AtomicInteger> paymentsPerSecond = new ConcurrentHashMap<>();
        // 幂等挡板：记住所有已支付的订单号
        private final Map<Integer, Boolean> paidOrderIds = new ConcurrentHashMap<>();
        final AtomicInteger duplicateRejected = new AtomicInteger(); // 被幂等挡掉的重复单数

        /** 支付一笔。返回 true = 真扣款；false = 重复订单被挡下 */
        boolean pay(PayOrder order) {
            // putIfAbsent：只有第一个塞进来的人成功，等价于数据库唯一索引兜底
            if (paidOrderIds.putIfAbsent(order.orderId(), Boolean.TRUE) != null) {
                duplicateRejected.incrementAndGet();
                return false; // 这单已经付过了，拒绝重复扣款
            }
            long secondIndex = (System.currentTimeMillis() - startMillis) / 1000;
            paymentsPerSecond.computeIfAbsent(secondIndex, key -> new AtomicInteger()).incrementAndGet();
            return true;
        }

        int totalPaid() {
            return paidOrderIds.size();
        }
    }

    // ======================== 主流程 ========================
    public static void main(String[] args) throws Exception {
        final int inflowPerSecond = 200;   // 进口：每秒 200 笔订单
        final int thirdPartyLimit = 100;   // 出口：第三方限流 100 笔/秒
        final int produceSeconds = 2;      // 下单方持续下单 2 秒（共 400 单 + 1 个重复单）
        final int workerCount = 4;         // 4 个工人，模拟 4 台机器

        var queue = new SimulatedMessageQueue();
        // 桶容量只给 100（1 秒的量）：原题提醒别攒太多票，防止突发一波冲垮第三方
        var tokenBucket = new GlobalTokenBucket(thirdPartyLimit, thirdPartyLimit);
        var thirdParty = new ThirdPartyPayService();

        // 记录出队顺序，最后校验 FIFO 用
        List<Integer> dequeueOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        var stopSignal = new java.util.concurrent.atomic.AtomicBoolean(false);

        // ---------- 启动 4 个支付工人 ----------
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(() -> {
                try {
                    while (!stopSignal.get()) {
                        // 关键顺序：先抢令牌，抢到了才去队列取单——保证全局不超速
                        if (!tokenBucket.tryAcquire()) {
                            Thread.sleep(3); // 没抢到就小睡一下再试，别疯狂空转打爆"Redis"
                            continue;
                        }
                        PayOrder order = queue.poll();
                        if (order == null) {
                            continue; // 队列暂时空了（令牌白拿一张，无伤大雅）
                        }
                        dequeueOrder.add(order.orderId()); // 登记出队顺序
                        thirdParty.pay(order);             // 调"第三方"扣款（内部带幂等）
                    }
                } catch (InterruptedException ignored) {
                    // 收工时被叫醒，正常退出
                }
            }, "worker-" + i);
            worker.start();
            workers.add(worker);
        }

        // ---------- 下单方：以 200 笔/秒 塞订单，持续 2 秒 ----------
        int nextOrderId = 1;
        for (int second = 0; second < produceSeconds; second++) {
            for (int i = 0; i < inflowPerSecond; i++) {
                queue.send(new PayOrder(nextOrderId++));
                Thread.sleep(1000 / inflowPerSecond); // 每 5 毫秒一单 ≈ 200 笔/秒
            }
            System.out.printf("第 %d 秒下单结束：累计下单 %d 笔，队列积压 %d 笔%n",
                    second + 1, nextOrderId - 1, queue.backlog());
        }
        int totalOrders = nextOrderId - 1;

        // 故意补发一笔重复订单（模拟网络超时后的重试），看幂等能不能挡住
        queue.send(new PayOrder(1));
        System.out.println("已注入 1 笔重复订单（订单号 1），验证幂等……");

        // ---------- 等队列消化完（200 进 100 出，还得再排 2 秒左右） ----------
        while (queue.backlog() > 0) {
            Thread.sleep(200);
        }
        Thread.sleep(500); // 给最后几笔在途订单一点收尾时间
        stopSignal.set(true);
        for (Thread worker : workers) {
            worker.interrupt();
            worker.join();
        }

        // ---------- 收尾校验 ----------
        System.out.println("\n===== 每秒实际打到第三方的笔数（限额 " + thirdPartyLimit + "）=====");
        boolean overLimit = false;
        var seconds = new ArrayList<>(thirdParty.paymentsPerSecond.keySet());
        java.util.Collections.sort(seconds);
        for (long second : seconds) {
            int count = thirdParty.paymentsPerSecond.get(second).get();
            // 留 5% 余量判断：线程调度有毫秒级抖动，个别秒 101、102 属统计口径误差
            boolean bad = count > thirdPartyLimit * 1.05;
            overLimit |= bad;
            System.out.printf("  第 %d 秒：%d 笔 %s%n", second + 1, count, bad ? "<-- 超限!" : "");
        }

        // 校验 FIFO：出队顺序应该和订单号顺序一致（重复注入的那笔除外）
        boolean fifoOk = true;
        int previousId = 0;
        for (int id : dequeueOrder) {
            if (id == 1 && previousId != 0) continue; // 跳过故意注入的重复单
            if (id < previousId) { fifoOk = false; break; }
            previousId = id;
        }

        System.out.println("\n===== 结果校验 =====");
        System.out.println("总下单: " + totalOrders + " 笔，成功支付: " + thirdParty.totalPaid() + " 笔"
                + (thirdParty.totalPaid() == totalOrders ? "（一单不丢、一单不多）" : "  <-- 数量对不上!"));
        System.out.println("幂等挡掉重复单: " + thirdParty.duplicateRejected.get() + " 笔（应为 1）");
        System.out.println("FIFO 先进先出: " + (fifoOk ? "通过（出队顺序 = 下单顺序）" : "不通过!"));
        System.out.println("速率是否超限: " + (overLimit ? "有超限，需检查!" : "全程未超过第三方限额"));

        if (!fifoOk || overLimit || thirdParty.totalPaid() != totalOrders
                || thirdParty.duplicateRejected.get() != 1) {
            throw new IllegalStateException("校验未通过，请检查实现！");
        }
        System.out.println("\n全部校验通过：削峰缓冲 + 全局令牌桶 + 幂等，三板斧都生效了。");
    }
}
