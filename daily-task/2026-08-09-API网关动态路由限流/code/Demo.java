// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * API 网关最小演示：动态路由 + 令牌桶限流 + 热更新
 *
 * 这个程序演示原题里讲的两件核心事：
 *   1) 动态路由：路由表存在内存里（模拟从配置中心 Nacos 拉取），并且支持运行时热更新——
 *      新增/修改路由后不用重启，下一个请求就能命中新规则。
 *   2) 限流：用"令牌桶"算法，按客户端 ID 限流。令牌桶就像银行发号机：每秒固定补几个号，
 *      请求来了先抢号，抢到才放行，没号就返回 429。
 *
 * 为了让新手能看懂，这里只做单机版演示；生产环境的分布式限流把"补号/抢号"换到 Redis + Lua 即可，
 * 思路完全一样。
 */
public class Demo {

    // ===================== 1. 路由部分 =====================

    /** 一条路由规则：路径前缀 -> 后端服务地址。像快递分拣中心的"地址对照表"。 */
    static class RouteRule {
        final String pathPrefix;   // 比如 /user
        final String serviceName;  // 比如 user-service
        final String address;      // 比如 127.0.0.1:8080

        RouteRule(String pathPrefix, String serviceName, String address) {
            this.pathPrefix = pathPrefix;
            this.serviceName = serviceName;
            this.address = address;
        }
    }

    /**
     * 路由表：key 是路径前缀，value 是规则。
     * 用 ConcurrentHashMap 是因为热更新时可能有请求正在读，保证并发安全。
     * 这就模拟了"网关内存里的路由表"，配置中心一推送，这里就被替换。
     */
    static final Map<String, RouteRule> routeTable = new ConcurrentHashMap<>();

    /**
     * 热更新路由：用新的一批规则整个替换内存路由表。
     * 模拟配置中心推送变更 → 网关监听到 → reload，全程不重启服务。
     */
    static void updateRoutes(List<RouteRule> newRules) {
        routeTable.clear();
        for (RouteRule rule : newRules) {
            routeTable.put(rule.pathPrefix, rule);
        }
        System.out.println("[网关] 路由表已热更新，当前规则数=" + routeTable.size());
    }

    /**
     * 路由匹配：按路径前缀找对应的后端服务。
     * 找不到就返回 null，表示没有匹配的路由（线上一般返回 404）。
     */
    static RouteRule matchRoute(String path) {
        for (RouteRule rule : routeTable.values()) {
            if (path.startsWith(rule.pathPrefix)) {
                return rule;
            }
        }
        return null;
    }

    // ===================== 2. 限流部分（令牌桶） =====================

    /**
     * 令牌桶：像银行发号机。
     *  - capacity：桶最多放几个号（突发上限）。
     *  - refillPerSecond：每秒补几个号（稳态速率）。
     *  - tokens：当前还剩几个号。
     * 请求来了调 tryAcquire()，抢到一个号才放行。
     */
    static class TokenBucket {
        final double capacity;        // 桶容量
        final double refillPerSecond;  // 每秒补充令牌数
        double tokens;                 // 当前令牌数
        long lastRefillNanos;          // 上次补充令牌的时间戳（纳秒）

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;            // 初始放满，允许开头小突发
            this.lastRefillNanos = System.nanoTime();
        }

        /** 抢一个令牌：抢到返回 true，没号返回 false。用 synchronized 保证多线程下不会超发。 */
        synchronized boolean tryAcquire() {
            // 先按经过的时间补令牌：这就是"发号机每隔一会儿就吐新号"的效果
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            double newTokens = elapsedNanos / 1_000_000_000.0 * refillPerSecond;
            if (newTokens > 0) {
                tokens = Math.min(capacity, tokens + newTokens); // 不能超过桶容量
                lastRefillNanos = now;
            }
            // 再判断有没有号可抢
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    /**
     * 限流器集合：每个客户端（用 IP 或 用户ID 做 key）一个独立的桶。
     * 这样"张三的请求太多被限流"不会连累"李四"。
     */
    static final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** 获取某个客户端的令牌桶，没有就新建一个：每秒 2 个令牌，桶容量 2（演示用小值）。 */
    static TokenBucket getBucket(String clientId) {
        return buckets.computeIfAbsent(clientId, k -> new TokenBucket(2, 2));
    }

    // ===================== 3. 网关处理请求 =====================

    /**
     * 网关处理一个请求，返回 HTTP 风格的状态码字符串。
     * 流程：路由匹配 -> 限流 -> 转发（这里模拟打印一下）。
     */
    static String handleRequest(String path, String clientId) {
        // 第一步：路由匹配（查"地址对照表"）
        RouteRule rule = matchRoute(path);
        if (rule == null) {
            return "404 Not Found（没有匹配的路由）: " + path;
        }

        // 第二步：限流（抢号）
        TokenBucket bucket = getBucket(clientId);
        if (!bucket.tryAcquire()) {
            return "429 Too Many Requests（限流了，请稍后再试）: " + clientId + " -> " + path;
        }

        // 第三步：转发到后端服务（这里只模拟，真实场景是发 HTTP 请求到 rule.address）
        // 把脏活累活扛在网关：实际还会在这里做 JWT 鉴权、记录耗时日志等
        return "200 OK（转发到 " + rule.serviceName + " @ " + rule.address + "）: " + path;
    }

    // ===================== 4. 主流程演示 =====================

    public static void main(String[] args) throws InterruptedException {
        // ① 初始化路由：模拟网关启动时从 Nacos 拉取到的路由表
        List<RouteRule> initRules = new ArrayList<>();
        initRules.add(new RouteRule("/user", "user-service", "127.0.0.1:8080"));
        initRules.add(new RouteRule("/order", "order-service", "127.0.0.1:8081"));
        updateRoutes(initRules);

        System.out.println("=== 场景一：正常请求，能命中路由并转发 ===");
        System.out.println(handleRequest("/user/info", "client-A"));
        System.out.println(handleRequest("/order/detail", "client-A"));

        System.out.println("\n=== 场景二：限流触发（client-A 连发 5 次 /user，每秒只补 2 个令牌）===");
        for (int i = 1; i <= 5; i++) {
            System.out.println("第" + i + "次: " + handleRequest("/user/info", "client-A"));
        }

        System.out.println("\n=== 场景三：换个客户端 client-B，不受 client-A 限流影响 ===");
        System.out.println(handleRequest("/user/info", "client-B"));

        System.out.println("\n=== 场景四：热更新路由——新增 /pay 路由，不重启立刻生效 ===");
        // 模拟管理后台改了配置，推送到配置中心，网关监听到变更后 reload
        List<RouteRule> newRules = new ArrayList<>(initRules);
        newRules.add(new RouteRule("/pay", "pay-service", "127.0.0.1:8082"));
        updateRoutes(newRules);
        // 等 1 秒，让 client-A 的令牌桶补点号，方便看 200
        TimeUnit.SECONDS.sleep(1);
        System.out.println(handleRequest("/pay/create", "client-A"));

        System.out.println("\n=== 场景五：不存在的路径，返回 404 ===");
        System.out.println(handleRequest("/unknown/xxx", "client-A"));

        System.out.println("\n演示结束。");
    }
}
