/*
 * API 网关设计：动态路由 + 令牌桶限流
 * ----------------------------------------------------------------------------
 * 用真实的 Redis（Jedis 连 localhost:6379）演示原题里的两件核心事：
 *   1) 动态路由：路由规则存在 Redis Hash 里当「配置中心的共享记事本」，网关内存里存一份缓存。
 *      管理后台改一下 Hash，网关 refresh 一次就生效 —— 不用重新发版，这就是热更新。
 *   2) 限流：令牌桶算法。桶的状态（剩余令牌数 + 上次补充时间）存在 Redis 里，
 *      「补令牌 + 扣令牌」整段写成 Lua 脚本交给 Redis 原子执行，
 *      所以多台网关共享同一个桶，不会各自放号导致总量超标。
 *
 * 五个场景：正常转发 / 限流触发 / 不同客户端互不影响 / 热更新新增路由 / 路由不存在返回 404
 * 前置条件：本机 Redis 7（见同目录 docker-compose.yml），无密码。
 */

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import redis.clients.jedis.Jedis;

public class Demo {

    /** 路由表所在的 Redis Hash：field = 路径前缀，value = 服务名@地址。 */
    static final String ROUTE_KEY = "gateway:routes";

    /** 限流参数：桶容量 2（允许的突发量），每秒补 2 个令牌（稳态速率）。 */
    static final int BUCKET_CAPACITY = 2;
    static final int REFILL_PER_SECOND = 2;

    /**
     * 令牌桶的 Lua 脚本，像银行的发号机：先按经过的时间补号，桶满了就不再放，然后判断能不能抢到一个号。
     * 整段脚本在 Redis 里单线程、不可打断地执行，多台网关并发调用也不会超发。
     */
    static final String TOKEN_BUCKET_LUA = """
            local tokens = tonumber(redis.call('hget', KEYS[1], 'tokens'))
            local last = tonumber(redis.call('hget', KEYS[1], 'last'))
            local now = tonumber(ARGV[3])
            if tokens == nil then tokens = tonumber(ARGV[1]); last = now end
            tokens = math.min(tonumber(ARGV[1]), tokens + (now - last) / 1000 * tonumber(ARGV[2]))
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
            redis.call('hset', KEYS[1], 'tokens', tokens, 'last', now)
            redis.call('pexpire', KEYS[1], 60000)
            return allowed
            """;

    /** 网关内存里的路由表缓存。真实网关会在启动时拉一份，之后靠监听配置变更来刷新。 */
    static final Map<String, String> routeCache = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.del(ROUTE_KEY);
            jedis.hset(ROUTE_KEY, "/user", "user-service@127.0.0.1:8080");
            jedis.hset(ROUTE_KEY, "/order", "order-service@127.0.0.1:8081");
            refreshRoutes(jedis);

            title("场景一：正常请求，命中路由并转发");
            System.out.println(handleRequest(jedis, "/user/info", "client-A"));
            System.out.println(handleRequest(jedis, "/order/detail", "client-A"));

            title("场景二：限流触发（client-A 连发 5 次，桶容量 2、每秒补 2 个令牌）");
            for (int i = 1; i <= 5; i++) {
                System.out.println("第 " + i + " 次: " + handleRequest(jedis, "/user/info", "client-A"));
            }

            title("场景三：换个客户端 client-B，不受 client-A 的限流影响");
            System.out.println(handleRequest(jedis, "/user/info", "client-B"));

            title("场景四：热更新路由 —— 新增 /pay，不重启立刻生效");
            // 管理后台改配置中心（Redis Hash），网关监听到变更后 reload 内存路由表
            jedis.hset(ROUTE_KEY, "/pay", "pay-service@127.0.0.1:8082");
            refreshRoutes(jedis);
            Thread.sleep(1000);   // 等 1 秒让 client-A 的桶补点令牌，方便看到 200
            System.out.println(handleRequest(jedis, "/pay/create", "client-A"));

            title("场景五：不存在的路径，返回 404");
            System.out.println(handleRequest(jedis, "/unknown/xxx", "client-A"));
        }
        System.out.println("\n=== 一句话提醒 ===");
        System.out.println("网关是所有请求的单点入口，必须多实例 + 负载均衡；路由和限流规则一定要支持热更新，");
        System.out.println("别等线上出事才发版改配置 —— 那会儿你改不动。");
    }

    /**
     * 网关处理一个请求：路由匹配 -> 限流抢号 -> 转发（这里只模拟打印）。
     * 真实网关在这一步还会做 JWT 鉴权、记录响应耗时和日志。
     */
    static String handleRequest(Jedis jedis, String path, String clientId) {
        String target = null;
        for (Map.Entry<String, String> rule : routeCache.entrySet()) {
            if (path.startsWith(rule.getKey())) {
                target = rule.getValue();
                break;
            }
        }
        if (target == null) {
            return "404 Not Found（没有匹配的路由）: " + path;
        }

        // 每个客户端一个独立的桶，这样「张三被限流」不会连累「李四」
        Object allowed = jedis.eval(TOKEN_BUCKET_LUA, List.of("ratelimit:" + clientId),
                List.of(String.valueOf(BUCKET_CAPACITY), String.valueOf(REFILL_PER_SECOND),
                        String.valueOf(System.currentTimeMillis())));
        if (!Long.valueOf(1L).equals(allowed)) {
            return "429 Too Many Requests（限流了，请稍后再试）: " + clientId + " -> " + path;
        }
        return "200 OK（转发到 " + target + "）: " + path;
    }

    /** 模拟「监听到配置中心变更 -> reload 内存路由表」，全程不重启服务。 */
    static void refreshRoutes(Jedis jedis) {
        routeCache.clear();
        routeCache.putAll(jedis.hgetAll(ROUTE_KEY));
        System.out.println("[网关] 路由表已热更新，当前规则数=" + routeCache.size() + " -> " + routeCache);
    }

    static void title(String text) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(text);
        System.out.println("======================================================================");
    }
}
