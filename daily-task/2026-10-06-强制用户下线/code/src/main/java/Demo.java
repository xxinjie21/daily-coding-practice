import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示「把已登录的用户踢下线」，全程使用真实 Redis（Jedis），没有任何内存对象冒充中间件。
 *
 * 前置条件：先起中间件（Redis 映射在宿主机 6380）
 *   cd code && docker compose up -d
 *
 * 演示四步：
 *   ① 登录 —— 会话（token）集中存进 Redis，带 30 分钟过期，而不是放在某台服务器的内存里
 *   ② 网关校验 —— 先查本地「已下线名单」，没命中再回 Redis 确认 token 还在不在
 *   ③ 踢人 —— 删掉 token，并往 Redis 频道发一条通知（真实 PUBLISH）
 *   ④ 通知生效 —— 每个网关实例的订阅线程收到通知后各自更新本地名单，后续请求立刻被拦
 * 最后对比两种踢人粒度：按设备踢（只踢一个登录点）与按账号踢（该账号全部登录点）
 */
public class Demo {

    static final String HOST = "127.0.0.1";
    static final int PORT = 6380;                       // 宿主机 6379 被用户自有 Redis 占用，改用 6380
    static final String TOKEN_KEY = "login:token:";     // 一个 token = 一次登录会话
    static final String USER_TOKENS_KEY = "login:user:";// 反向索引：一个用户名下有哪些 token（Set）
    static final String LOGOUT_CHANNEL = "user-logout"; // 踢人通知频道

    // 每个网关实例各存一份「已被踢下线」的本地名单：命中就直接拒绝，连 Redis 都不用查。
    // 这是进程内职责（本地缓存），用 ConcurrentHashMap 是合理的。
    static final Set<String> gateway1Offline = ConcurrentHashMap.newKeySet();
    static final Set<String> gateway2Offline = ConcurrentHashMap.newKeySet();

    // 订阅句柄，退出前要主动退订，否则订阅连接会一直阻塞在 subscribe() 里
    static final List<JedisPubSub> subscriptions = new CopyOnWriteArrayList<>();

    static final AtomicInteger loginSeq = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        JedisPool pool = new JedisPool(HOST, PORT);
        Jedis redis = pool.getResource();          // 业务命令用的连接
        try {
            // 订阅必须独占连接（订阅期间这条连接不能再发别的命令），所以另开两条
            startGateway(pool.getResource(), "网关-1", gateway1Offline);
            startGateway(pool.getResource(), "网关-2", gateway2Offline);
            Thread.sleep(300);                     // 等订阅真正建立，否则通知会漏掉

            System.out.println("=== ① 登录：会话集中存进 Redis ===");
            String deviceA = login(redis, "u1001"); // u1001 在手机上登录
            String deviceB = login(redis, "u1001"); // u1001 又在电脑上登录
            System.out.println("u1001 有两个登录点：" + deviceA + "、" + deviceB);

            System.out.println("\n=== ② 正常请求：两个网关都放行 ===");
            check(gateway1Offline, redis, deviceA, "网关-1");
            check(gateway2Offline, redis, deviceB, "网关-2");

            System.out.println("\n=== ③ 按设备踢：只让手机那个登录点下线 ===");
            kickDevice(redis, deviceA);
            Thread.sleep(300);                     // 等 Pub/Sub 通知到达各网关
            check(gateway1Offline, redis, deviceA, "网关-1");
            check(gateway2Offline, redis, deviceB, "网关-2"); // 另一个登录点不受影响

            System.out.println("\n=== ④ 按账号踢：u1001 全部登录点一起下线 ===");
            kickAccount(redis, "u1001");
            Thread.sleep(300);
            check(gateway1Offline, redis, deviceB, "网关-1");
        } finally {
            for (JedisPubSub pubsub : subscriptions) {
                pubsub.unsubscribe();              // 主动退订，让订阅线程正常结束
            }
            Thread.sleep(200);
            redis.close();
            pool.close();
        }
    }

    /** 登录：把会话写进 Redis，同时记下「这个账号有哪些 token」，方便按账号一次性全踢。 */
    static String login(Jedis redis, String userId) {
        String token = "tk-" + userId + "-" + loginSeq.incrementAndGet();
        redis.set(TOKEN_KEY + token, userId, SetParams.setParams().ex(1800)); // 30 分钟不活动自动失效
        redis.sadd(USER_TOKENS_KEY + userId, token);
        redis.expire(USER_TOKENS_KEY + userId, 1800);
        return token;
    }

    /** 网关校验：本地名单优先（最快），没命中再回 Redis 确认会话还在不在。 */
    static void check(Set<String> offlineSet, Jedis redis, String token, String gatewayName) {
        boolean allowed = !offlineSet.contains(token) && redis.exists(TOKEN_KEY + token);
        System.out.println("[" + gatewayName + "] " + token + " → "
                + (allowed ? "放行" : "拒绝（已被踢下线）"));
    }

    /** 按设备踢：让这一个 token 失效，并广播出去。 */
    static void kickDevice(Jedis redis, String token) {
        String userId = redis.get(TOKEN_KEY + token);
        redis.del(TOKEN_KEY + token);
        redis.srem(USER_TOKENS_KEY + userId, token);
        redis.publish(LOGOUT_CHANNEL, token);      // 真实 PUBLISH，所有网关实例都会收到
    }

    /** 按账号踢：把该账号名下所有 token 逐个作废，并逐个广播。 */
    static void kickAccount(Jedis redis, String userId) {
        Set<String> tokens = redis.smembers(USER_TOKENS_KEY + userId);
        for (String token : tokens) {
            redis.del(TOKEN_KEY + token);
            redis.publish(LOGOUT_CHANNEL, token);
        }
        redis.del(USER_TOKENS_KEY + userId);
        System.out.println("（账号 " + userId + " 共 " + tokens.size() + " 个登录点被踢）");
    }

    /** 一个网关实例的订阅线程：收到下线通知就往本地名单里记一笔。 */
    static void startGateway(Jedis subConn, String gatewayName, Set<String> offlineSet) {
        JedisPubSub pubsub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String token) {
                offlineSet.add(token);
                System.out.println("  [" + gatewayName + "] 收到下线通知：" + token + " → 记入本地名单");
            }
        };
        subscriptions.add(pubsub);
        Thread thread = new Thread(() -> subConn.subscribe(pubsub, LOGOUT_CHANNEL), gatewayName);
        thread.setDaemon(true);
        thread.start();
    }
}
