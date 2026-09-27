// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题：调用第三方接口（支付/短信/物流/地图）要注意什么？
// 一句话：第三方的可用性你管不了，但「被它拖垮」是可以避免的。这个 Demo 把
// 几道防线用真实组件跑一遍：
//
//   ① 超时控制   —— okhttp 真的配 connectTimeout / readTimeout / callTimeout
//   ② 带退避重试 —— resilience4j Retry（指数退避）；有副作用的接口一次都不重试
//   ③ 熔断降级   —— resilience4j CircuitBreaker（错误率 > 50% 跳闸，期间走降级）
//   ④ 幂等去重   —— Redis（Jedis）的 SET NX EX，同一个任务 ID 只处理一次
//   ⑤ 调用限流   —— Redis 的 INCR + EXPIRE 计数器，给第三方也给自己留活路
//
// 生活比喻：叫外卖。不设超时 = 站门口等到天荒地老；无脑重试 = 把商家电话打爆；
// 熔断 = 这家店连着不出餐就先停几天；不幂等 = 手抖点两次送来两份还扣两次钱。
//
// 前置条件：本机起一个 Redis（见同目录 docker-compose.yml，localhost:6379 无密码）。
// 被调用的第三方地址见 THIRD_PARTY_URL —— 本机没起这个服务时，你会看到失败和熔断，
// 但防护逻辑照样生效，正好能观察到「对方挂了，我这边没被拖死」。
// 运行：mvn -q compile exec:java
// ============================================================================

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

public class Demo {

    /** 被调用的第三方接口。真实项目换成支付/短信/物流的真实域名。 */
    static final String THIRD_PARTY_URL = "http://localhost:8080/logistics/shipment";

    /** 同一个用户每分钟最多调用第三方几次（限流用）。 */
    static final int MAX_CALLS_PER_MINUTE = 3;

    public static void main(String[] args) {
        System.out.println("======== 调用第三方接口的防护演示（okhttp + resilience4j + Redis） ========");
        System.out.println("被调用的第三方地址：" + THIRD_PARTY_URL);

        // ① 超时是「最重要的第一道防线」：三个都配上，任何一个不配都可能把线程占死
        //    connectTimeout 连接超时（TCP 握手多久算失败）
        //    readTimeout    读取超时（连上了但对方多久不回数据算失败）
        //    callTimeout    整体超时（一次调用从发起到拿到响应的总上限）
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .callTimeout(Duration.ofSeconds(5))
                .build();

        demoTimeout(client);
        demoRetry(client);
        demoCircuitBreaker(client);

        try (Jedis redis = new Jedis("localhost", 6379)) {
            redis.flushDB(); // 演示用：每次从干净状态开始
            demoIdempotency(redis);
            demoRateLimit(redis);
        }

        System.out.println("\n======== 演示结束 ========");
    }

    // ========================================================================
    // ① 超时控制：等太久就放弃，别把自己线程占死
    // ========================================================================
    private static void demoTimeout(OkHttpClient client) {
        System.out.println("\n【① 超时控制】okhttp 三个超时都配上，别等到天荒地老");
        System.out.println("   connectTimeout=" + client.connectTimeoutMillis() + "ms, readTimeout="
                + client.readTimeoutMillis() + "ms, callTimeout=" + client.callTimeoutMillis() + "ms");
        try {
            System.out.println("   结果：" + callThirdParty(client, "ORDER-1001"));
        } catch (IOException e) {
            System.out.println("   结果：调用被放弃（" + e.getClass().getSimpleName()
                    + "），线程立刻空出来服务别的请求，不会被对方占死");
        }
    }

    // ========================================================================
    // ② 重试：网络抖动可以重试，有「副作用」的操作绝对不能
    // ========================================================================
    private static void demoRetry(OkHttpClient client) {
        System.out.println("\n【② 带退避的重试】resilience4j Retry，退避 200ms -> 400ms");

        // 查询类接口：没有副作用，失败了重试 3 次没关系，反正不改变对方状态
        Retry queryRetry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryExceptions(UncheckedIOException.class)
                .build()).retry("logistics-query");

        // 支付/下单类接口：有副作用，maxAttempts=1 —— 这是原则问题，不是性能问题
        Retry paymentRetry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1)
                .build()).retry("logistics-payment");

        System.out.println(" 场景 A：查询物流状态（无副作用，最多试 3 次）");
        System.out.println("   结果：" + attempt(queryRetry, client, "ORDER-2001"));

        System.out.println(" 场景 B：调用支付接口（有副作用，绝不自动重试）");
        System.out.println("   结果：" + attempt(paymentRetry, client, "ORDER-2002") + "，交给人工/对账处理");
        System.out.println("   → 宁可不做，也不能重复扣款。");
    }

    /** 执行一次（可能带重试的）第三方调用，失败就把原因说清楚。 */
    private static String attempt(Retry retry, OkHttpClient client, String orderId) {
        try {
            return retry.executeSupplier(thirdPartyCall(client, orderId));
        } catch (RuntimeException e) {
            return "失败：" + rootMessage(e);
        }
    }

    // ========================================================================
    // ③ 熔断降级：像家里的空气开关，短路了就跳闸，别一直烧
    //    关闭(正常) -> 请求正常发出；打开(熔断) -> 请求根本不发，直接走降级；
    //    半开(试探) -> 放一小部分请求过去试试，好了就恢复。
    // ========================================================================
    private static void demoCircuitBreaker(OkHttpClient client) {
        System.out.println("\n【③ 熔断降级】错误率超过 50% 就跳闸，期间直接走降级");

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)                            // 观察最近 10 次调用
                .minimumNumberOfCalls(5)                          // 至少攒够 5 次才开始算错误率
                .failureRateThreshold(50)                         // 错误率超过 50% 跳闸
                .waitDurationInOpenState(Duration.ofSeconds(3))   // 跳闸后停 3 秒（真实项目常设几分钟）
                .build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("logistics");

        for (int requestNo = 1; requestNo <= 12; requestNo++) {
            try {
                String result = circuitBreaker.executeSupplier(thirdPartyCall(client, "ORDER-300" + requestNo));
                System.out.println("   第 " + requestNo + " 次请求：" + result);
            } catch (CallNotPermittedException e) {
                // 熔断期间请求根本不发出去，直接返回本地降级值（真实项目可能是缓存里的旧数据）
                System.out.println("   第 " + requestNo + " 次请求：熔断中，直接走降级 -> 返回默认值「运单待补」");
            } catch (RuntimeException e) {
                System.out.println("   第 " + requestNo + " 次请求：失败 -> " + rootMessage(e)
                        + "（熔断器状态 " + circuitBreaker.getState() + "）");
            }
        }
        System.out.println("   → 熔断器最终状态：" + circuitBreaker.getState()
                + "。错太多就先跳闸，别再继续烧。");
    }

    // ========================================================================
    // ④ 幂等：同一个请求来两次，效果只算一次
    //    做法：给每次操作一个唯一任务 ID，用 SET key value NX EX 去「占坑」。
    // ========================================================================
    private static void demoIdempotency(Jedis redis) {
        System.out.println("\n【④ 幂等去重】同一个任务 ID 十分钟内重复来，只处理第一次");

        String taskId = "CALLBACK-ORDER-4001";
        for (int times = 1; times <= 3; times++) {
            // NX：key 不存在才写得进去（第一次占坑成功）；EX 600：10 分钟后自动过期
            String acquired = redis.set("idem:" + taskId, "1", SetParams.setParams().nx().ex(600));
            if ("OK".equals(acquired)) {
                System.out.println("   第 " + times + " 次回调：占坑成功（SET NX EX 返回 OK），真正执行「通知第三方发货」");
            } else {
                System.out.println("   第 " + times + " 次回调：10 分钟内已处理过，直接忽略（当成功返回）");
            }
        }
        System.out.println("   → 网络抖动导致我们重发、或者对方超时其实已经处理成功，都不会重复发货。");
    }

    // ========================================================================
    // ⑤ 限流：给第三方留活路，也保护自己
    // ========================================================================
    private static void demoRateLimit(Jedis redis) {
        System.out.println("\n【⑤ 调用限流】INCR + EXPIRE 计数器，每分钟最多 " + MAX_CALLS_PER_MINUTE + " 次");

        String quotaKey = "limit:logistics:user-9527";
        for (int times = 1; times <= 5; times++) {
            long usedTimes = redis.incr(quotaKey);
            if (usedTimes == 1) {
                redis.expire(quotaKey, 60); // 第一次调用时挂上 60 秒过期，到点自动清零
            }
            System.out.println("   第 " + times + " 次调用 -> "
                    + (usedTimes <= MAX_CALLS_PER_MINUTE ? "放行" : "超过每分钟 " + MAX_CALLS_PER_MINUTE + " 次，被限流"));
        }
    }

    // ========================================================================
    // 小工具
    // ========================================================================

    /** 真的发一次 HTTP 请求。okhttp 超时到点会直接抛 IOException，不会无限等。 */
    private static String callThirdParty(OkHttpClient client, String orderId) throws IOException {
        Request request = new Request.Builder()
                .url(THIRD_PARTY_URL + "?orderId=" + orderId)
                .header("X-Idempotency-Key", orderId) // 有副作用的接口务必带上幂等键
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("第三方返回 HTTP " + response.code()); // 5xx 当作可重试的失败
            }
            return "HTTP " + response.code() + " -> " + response.body().string();
        }
    }

    /** 把「会抛受检异常的调用」包成 Supplier，方便交给 resilience4j 装饰。 */
    private static Supplier<String> thirdPartyCall(OkHttpClient client, String orderId) {
        return () -> {
            try {
                return callThirdParty(client, orderId);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** 把异常链最底层的真实原因挖出来，打印时别只看到一层包装。 */
    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
