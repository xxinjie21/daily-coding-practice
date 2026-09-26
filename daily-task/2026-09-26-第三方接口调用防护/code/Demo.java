// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   题目是「调用第三方接口应注意哪些问题」。我们用纯 Java（不引任何外部依赖）
//   把原题里提到的六道防线，挑最核心的四道做一个能跑起来的小演示：
//
//     ① 超时控制     —— 等太久就放弃，别把自己线程占死
//     ② 带退避的重试 —— 网络抖动可以重试；支付这种「有副作用」的绝对不能重试
//     ③ 熔断降级     —— 错误率超过 50% 就跳闸几分钟，期间直接走降级（本地缓存/默认值）
//     ④ 幂等去重     —— 同一个任务 ID 十分钟内重复来，直接忽略（真实项目用 Redis 存）
//     ⑤ 监控埋点     —— 顺手统计成功率和 P95 耗时
//
// 程序里有一个「假的第三方物流接口」，我们可以让它故意失败、故意变慢，
// 用来观察上面四道防线分别是怎么起作用的。
//
// 说明：真实项目里超时用 HTTP 客户端的 connectTimeout / readTimeout 配置，
//       幂等用 Redis 的 setnx + 过期时间。这里为了让 Demo 能独立跑起来，
//       分别用 CompletableFuture 的超时和 ConcurrentHashMap 来「模拟」同样的语义。

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Demo {

    // ==================================================================
    // 一、模拟「第三方物流接口」：它会睡一会儿（模拟网络慢），也可能直接报错
    // ==================================================================
    static class ThirdPartyLogisticsClient {

        // 还剩几次调用要故意失败（模拟对方服务抖动）
        private int remainingFailures;
        // 每次调用假装花多少毫秒（模拟网络/对方处理耗时）
        private final long fakeLatencyMillis;

        ThirdPartyLogisticsClient(int failTimes, long latencyMillis) {
            this.remainingFailures = failTimes;
            this.fakeLatencyMillis = latencyMillis;
        }

        /**
         * 调用第三方下单，成功时返回物流单号。
         * 注意：这是个「有副作用」的接口 —— 调一次，对方就真的建了一个运单。
         */
        String createShipment(String orderId) {
            if (fakeLatencyMillis > 0) {
                try {
                    Thread.sleep(fakeLatencyMillis);
                } catch (InterruptedException e) {
                    // 被超时逻辑打断：把中断状态还回去，然后按「调用失败」处理
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("调用被中断（可能是超时后主动放弃）");
                }
            }
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new IllegalStateException("第三方返回 500：服务暂时不可用");
            }
            return "运单号-SF" + orderId;
        }
    }

    // ==================================================================
    // 二、第一道防线：超时控制
    //     思路：把调用丢到另一个线程去做，主线程最多只等 timeoutMillis。
    //           等不到就放弃，把连接/线程让出来给别的请求用。
    // ==================================================================
    static String callWithTimeout(ThirdPartyLogisticsClient client,
                                  String orderId,
                                  long timeoutMillis) {
        // supplyAsync：在别的线程里执行这次调用，主线程可以设个「闹钟」
        CompletableFuture<String> callFuture =
                CompletableFuture.supplyAsync(() -> client.createShipment(orderId));
        try {
            // 最多等 timeoutMillis 毫秒，到点还没结果就抛 TimeoutException
            return callFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            callFuture.cancel(true); // 告诉那个线程「别做了」
            throw new IllegalStateException("调用超时（超过 " + timeoutMillis + " 毫秒）");
        } catch (ExecutionException e) {
            // 对方真的报错了，把原因原样抛出来
            throw new IllegalStateException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("当前线程被中断");
        }
    }

    // ==================================================================
    // 三、第二道防线：重试（带退避）
    //     关键规则：
    //       - 查询类接口（没有副作用）：失败可以重试 2~3 次，每次多等一会儿（退避）
    //       - 支付/下单类（有副作用）：一次都不许重试，否则可能重复扣款
    // ==================================================================
    static String callWithRetry(Callable<String> action,
                               int maxRetryTimes,
                               long baseBackoffMillis,
                               boolean hasSideEffect) {
        // 有副作用的操作，直接把重试次数清零 —— 这是原则问题，不是性能问题
        int allowedRetryTimes = hasSideEffect ? 0 : maxRetryTimes;
        if (hasSideEffect) {
            System.out.println("   [重试] 该接口带副作用（会改变对方数据），按规则不自动重试");
        }

        long waitMillis = baseBackoffMillis;
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt > allowedRetryTimes) {
                    throw new IllegalStateException("重试 " + allowedRetryTimes + " 次后仍失败：" + e.getMessage());
                }
                System.out.println("   第 " + attempt + " 次失败（" + e.getMessage()
                        + "），等 " + waitMillis + " 毫秒后退避重试");
                sleepQuietly(waitMillis);
                waitMillis = waitMillis * 2; // 退避：1 秒、2 秒、4 秒……给对方喘息时间
            }
        }
    }

    // ==================================================================
    // 四、第三道防线：熔断器（像家里的空气开关，短路了就跳闸）
    //     规则：最近 10 次调用里，错误率超过 50% 就「跳闸」，跳闸期间不再发请求，
    //           直接走降级逻辑；过一段时间再放请求过去试探。
    // ==================================================================
    static class CircuitBreaker {

        private final int windowSize = 10;              // 观察窗口：最近 10 次
        private final double errorRateThreshold = 0.5;  // 错误率阈值：50%
        private final long openDurationMillis = 3000;   // 跳闸后停多久（真实项目一般几分钟）

        // 最近几次调用的结果，true 表示成功、false 表示失败
        private final Deque<Boolean> recentResults = new ArrayDeque<>();
        // 熔断打开到什么时刻为止
        private long openUntilMillis = 0;

        /** 是否放行这次请求（false 表示正在熔断，请走降级） */
        synchronized boolean allowRequest() {
            return System.currentTimeMillis() >= openUntilMillis;
        }

        /** 记录一次调用结果，并判断要不要跳闸 */
        synchronized void recordResult(boolean success) {
            recentResults.addLast(success);
            if (recentResults.size() > windowSize) {
                recentResults.removeFirst(); // 只保留最近 windowSize 次
            }
            if (recentResults.size() < windowSize) {
                return; // 样本太少，先不判断
            }
            long failureCount = recentResults.stream().filter(ok -> !ok).count();
            double errorRate = (double) failureCount / windowSize;
            if (errorRate > errorRateThreshold) {
                openUntilMillis = System.currentTimeMillis() + openDurationMillis;
                recentResults.clear(); // 重新开始统计
                System.out.println("   [熔断器] 最近 " + windowSize + " 次错误率 "
                        + Math.round(errorRate * 100) + "% 超过 50%，跳闸 "
                        + openDurationMillis / 1000 + " 秒，期间直接走降级");
            }
        }
    }

    // ==================================================================
    // 五、第四道防线：幂等去重
    //     同一件事（同一个任务 ID）重复来了，只处理第一次。
    //     真实项目用 Redis 存 key + 过期时间；这里用 ConcurrentHashMap 模拟同样的语义。
    // ==================================================================
    static class IdempotencyGuard {

        // key = 任务 ID，value = 这个 key 什么时候过期
        private final Map<String, Long> taskIdToExpireAt = new ConcurrentHashMap<>();
        private final long ttlMillis = 10 * 60 * 1000; // 10 分钟

        /**
         * 尝试「占坑」。
         * 返回 true  = 第一次来，你可以放心执行；
         * 返回 false = 10 分钟内来过，直接当成功忽略。
         */
        boolean tryAcquire(String taskId) {
            long now = System.currentTimeMillis();
            // 顺手清掉已经过期的记录（真实项目由 Redis 自动过期，不用自己清）
            taskIdToExpireAt.entrySet().removeIf(entry -> entry.getValue() < now);
            Long previousExpireAt = taskIdToExpireAt.putIfAbsent(taskId, now + ttlMillis);
            return previousExpireAt == null;
        }
    }

    // ==================================================================
    // 六、监控埋点：统计成功率和 P95 耗时
    //     P95 的意思是：把所有耗时从小到大排队，95% 的请求都快于这个值，
    //     它比平均值更能反映「最慢的那批请求有多慢」。
    // ==================================================================
    static class CallMetrics {

        private int totalCount = 0;
        private int successCount = 0;
        private final List<Long> costMillisList = new ArrayList<>();

        void record(boolean success, long costMillis) {
            totalCount++;
            if (success) {
                successCount++;
            }
            costMillisList.add(costMillis);
        }

        void printReport() {
            if (totalCount == 0) {
                System.out.println("   暂无调用记录");
                return;
            }
            List<Long> sorted = new ArrayList<>(costMillisList);
            Collections.sort(sorted);
            // P95 位置：比如 20 条记录，取第 19 条（下标 18）
            int p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
            p95Index = Math.max(0, Math.min(p95Index, sorted.size() - 1));

            // 这里用字符串拼接而不是 printf，避免个别终端下中文格式串显示异常
            String successRate = String.format("%.1f", successCount * 100.0 / totalCount);
            System.out.println("   总调用 " + totalCount + " 次，成功 " + successCount
                    + " 次，成功率 " + successRate + "%，P95 耗时 " + sorted.get(p95Index) + " 毫秒");
        }
    }

    // ==================================================================
    // 主流程：依次演示四道防线
    // ==================================================================
    public static void main(String[] args) {
        System.out.println("======== 调用第三方接口的防护演示 ========");

        demoTimeout();
        demoRetry();
        demoCircuitBreaker();
        demoIdempotency();
        demoMetrics();

        System.out.println("======== 演示结束 ========");
    }

    /** 演示 ① 超时控制：对方太慢，我们主动放弃，不被它拖住 */
    private static void demoTimeout() {
        System.out.println();
        System.out.println("【① 超时控制】对方要 800 毫秒才回，我们只肯等 300 毫秒");
        ThirdPartyLogisticsClient slowClient = new ThirdPartyLogisticsClient(0, 800);
        try {
            String shipmentNo = callWithTimeout(slowClient, "ORDER-1001", 300);
            System.out.println("   结果：" + shipmentNo);
        } catch (Exception e) {
            System.out.println("   结果：调用被放弃 —— " + e.getMessage());
            System.out.println("   好处：线程立刻空出来去服务别的请求，不会一直被占着");
        }
    }

    /** 演示 ② 重试：查询类可以退避重试；支付类一次都不重试 */
    private static void demoRetry() {
        System.out.println();
        System.out.println("【② 带退避的重试】对方前 2 次都失败，第 3 次成功");

        // 场景 A：查询类接口（没有副作用），允许重试
        System.out.println(" 场景 A：查询物流状态（无副作用，允许重试）");
        ThirdPartyLogisticsClient flakyClient = new ThirdPartyLogisticsClient(2, 0);
        try {
            String result = callWithRetry(() -> flakyClient.createShipment("ORDER-2001"),
                    3, 200, false);
            System.out.println("   结果：最终成功 -> " + result);
        } catch (Exception e) {
            System.out.println("   结果：失败 —— " + e.getMessage());
        }

        // 场景 B：支付类接口（有副作用），坚决不自动重试
        System.out.println(" 场景 B：调用支付接口（有副作用，禁止自动重试）");
        ThirdPartyLogisticsClient payClient = new ThirdPartyLogisticsClient(2, 0);
        try {
            String result = callWithRetry(() -> payClient.createShipment("ORDER-2002"),
                    3, 200, true);
            System.out.println("   结果：成功 -> " + result);
        } catch (Exception e) {
            System.out.println("   结果：直接失败，交给人工/对账处理 —— " + e.getMessage());
            System.out.println("   好处：宁可不做，也不能重复扣款");
        }
    }

    /** 演示 ③ 熔断降级：错误率超过 50% 就跳闸，之后直接走降级值 */
    private static void demoCircuitBreaker() {
        System.out.println();
        System.out.println("【③ 熔断降级】连着失败，错误率超过 50% 就跳闸");
        CircuitBreaker breaker = new CircuitBreaker();
        ThirdPartyLogisticsClient alwaysBrokenClient = new ThirdPartyLogisticsClient(100, 0);

        for (int requestNo = 1; requestNo <= 14; requestNo++) {
            if (!breaker.allowRequest()) {
                // 熔断期间：请求根本不发出去，直接返回降级值（真实项目可能是本地缓存）
                System.out.println("   第 " + requestNo + " 次请求：熔断中，直接走降级 -> 返回默认值「运单待补」");
                continue;
            }
            try {
                String shipmentNo = alwaysBrokenClient.createShipment("ORDER-300" + requestNo);
                breaker.recordResult(true);
                System.out.println("   第 " + requestNo + " 次请求：成功 -> " + shipmentNo);
            } catch (Exception e) {
                breaker.recordResult(false);
                System.out.println("   第 " + requestNo + " 次请求：失败 -> " + e.getMessage());
            }
        }
    }

    /** 演示 ④ 幂等：同一个任务 ID 十分钟内重复来，只执行第一次 */
    private static void demoIdempotency() {
        System.out.println();
        System.out.println("【④ 幂等去重】同一个任务 ID 重复回调，只处理第一次");
        IdempotencyGuard guard = new IdempotencyGuard();
        String callbackTaskId = "CALLBACK-ORDER-4001";

        for (int times = 1; times <= 3; times++) {
            if (guard.tryAcquire(callbackTaskId)) {
                System.out.println("   第 " + times + " 次回调：占坑成功，真正执行「通知第三方发货」");
            } else {
                System.out.println("   第 " + times + " 次回调：10 分钟内已处理过，直接忽略（当成功返回）");
            }
        }
    }

    /** 演示 ⑤ 监控埋点：记录每次调用的成败和耗时，最后算成功率与 P95 */
    private static void demoMetrics() {
        System.out.println();
        System.out.println("【⑤ 监控埋点】统计成功率与 P95 耗时");
        CallMetrics metrics = new CallMetrics();
        // 每次调用假装花 5 毫秒，这样 P95 不会是 0，看起来更真实
        ThirdPartyLogisticsClient client = new ThirdPartyLogisticsClient(3, 5);

        for (int requestNo = 1; requestNo <= 10; requestNo++) {
            long startMillis = System.currentTimeMillis();
            boolean success;
            try {
                client.createShipment("ORDER-500" + requestNo);
                success = true;
            } catch (Exception e) {
                success = false;
            }
            metrics.record(success, System.currentTimeMillis() - startMillis);
        }
        metrics.printReport();
    }

    /** 睡一会儿，被打断也不往外抛异常（Demo 里简化处理） */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
