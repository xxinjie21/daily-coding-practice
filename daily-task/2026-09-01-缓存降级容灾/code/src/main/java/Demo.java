// 【这个程序在干嘛】题目：系统靠 Redis 顶着高并发，万一 Redis 突然挂了，请求全砸到 MySQL 上，
// 数据库扛不住、整站雪崩，怎么办？用真实的 Jedis + resilience4j 熔断器演示四招：
//   1 正常态（热点全命中 Redis）/ 2 Redis 挂了（熔断器自动断开 + 降级开关 + 本地缓存兜底）/
//   3 闸机限流（Semaphore 把打向下游的并发锁在 50 以内）/ 4 持久化恢复（appendonly / appendfsync）
//
// 【前置条件】先起 Redis：docker compose up -d redis（localhost:6379，无密码）。
// 「Redis 挂了」不用真去 kill 它，把连接指向一个没人监听的端口（localhost:6399）效果一样。
// 编译：mvn -o -q compile   运行：mvn -o exec:java

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    static final int REDIS_PORT = 6379;
    /** 指向没人监听的端口 = Redis 挂了。用它演示熔断和降级，不用真去 kill Redis。 */
    static final int DEAD_REDIS_PORT = 6399;
    /** 闸机放行数：宁可让几个请求排队，也别把下游冲垮。 */
    static final int DOWNSTREAM_LIMIT = 50;

    record CacheEntry(String value, long expireAtMillis) {
    }

    /** 本地缓存：JVM 自己内存里的一小块（真实项目用 Caffeine）。带保质期，过期就当没有。 */
    static class LocalCache {
        private final Map<String, CacheEntry> store = new ConcurrentHashMap<>();

        String get(String key) {
            CacheEntry entry = store.get(key);
            if (entry == null || System.currentTimeMillis() > entry.expireAtMillis()) {
                return null;
            }
            return entry.value();
        }

        void put(String key, String value, long ttlMillis) {
            store.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMillis));
        }
    }

    /**
     * 下游服务：真实项目里这一层就是 MySQL。
     * 只记两件事——被打了多少次、同时有多少线程在打，用来证明闸机真的拦住了。
     */
    static class DownstreamService {
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger peakConcurrent = new AtomicInteger();
        private final AtomicInteger hits = new AtomicInteger();

        String load(String key) {
            int now = concurrent.incrementAndGet();
            peakConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5);   // 模拟一次查库的耗时
                hits.incrementAndGet();
                return "data-" + key;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }

    // 取数链路：降级开关 → Redis（套熔断器）→ 本地缓存 → 闸机 → 下游 → 回填
    static class CacheService implements AutoCloseable {

        private final JedisPool jedisPool;
        private final CircuitBreaker redisBreaker;
        private final LocalCache localCache = new LocalCache();
        private final DownstreamService downstream = new DownstreamService();
        private final Semaphore downstreamPermits;
        /** 降级开关：真实项目放 Nacos / Apollo，出事拨一下，不用改代码重启。 */
        private volatile boolean degraded;

        CacheService(int port, int maxDownstreamThreads) {
            this.jedisPool = new JedisPool(new HostAndPort("localhost", port),
                    DefaultJedisClientConfig.builder()
                            .connectionTimeoutMillis(200).socketTimeoutMillis(200)   // 别让请求干等
                            .build());
            this.redisBreaker = CircuitBreaker.of("redis", CircuitBreakerConfig.custom()
                    .slidingWindowSize(10)          // 最近 10 次调用里统计失败率
                    .minimumNumberOfCalls(5)        // 至少攒够 5 次才开始判断
                    .failureRateThreshold(50)       // 失败率过半就断开
                    .waitDurationInOpenState(Duration.ofSeconds(5))
                    .build());
            this.downstreamPermits = new Semaphore(maxDownstreamThreads);
        }

        void openDegradeSwitch() {
            degraded = true;
        }

        String breakerState() {
            return redisBreaker.getState().name();
        }

        String get(String key) {
            if (!degraded) {
                String cached = readRedis(key);
                if (cached != null) {
                    return cached;   // 命中 Redis，最快
                }
            }
            String local = localCache.get(key);          // 第二层：本地缓存顶热点
            if (local != null) {
                return local;
            }
            downstreamPermits.acquireUninterruptibly();  // 第三层：闸机，把并发锁住
            try {
                String value = downstream.load(key);
                localCache.put(key, value, 60_000);
                writeBackRedis(key, value);
                return value;
            } finally {
                downstreamPermits.release();
            }
        }

        /**
         * 读 Redis。连「借连接」都放在熔断器里面——借连接时就会真的建 TCP 连接，
         * 连接失败也算一次失败，熔断器才记得上账。断开后这里直接短路，不再等连接超时。
         */
        private String readRedis(String key) {
            try {
                return redisBreaker.executeSupplier(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        return jedis.get(key);
                    }
                });
            } catch (RuntimeException redisUnavailable) {
                return null;   // 交给本地缓存 + 下游兜底
            }
        }

        private void writeBackRedis(String key, String value) {
            if (degraded) {
                return;
            }
            try {
                redisBreaker.executeRunnable(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.set(key, value, SetParams.setParams().px(60_000));
                    }
                });
            } catch (RuntimeException ignored) {
                // 回填失败不影响本次返回
            }
        }

        @Override
        public void close() {
            jedisPool.close();
        }
    }

    // ===================== 实验一：正常态 =====================
    static void experimentOneNormal() throws Exception {
        System.out.println("=== 实验一：正常态（Redis 在线）===");
        List<String> hotKeys = hotKeys(20);
        try (CacheService service = new CacheService(REDIS_PORT, DOWNSTREAM_LIMIT)) {
            for (String key : hotKeys) {
                service.get(key);   // 预热：每个 key 查一次下游，顺手写回 Redis
            }
            flood(service, repeat(hotKeys, 200));
            System.out.println("  200 次请求打 20 个热点，下游被打 " + service.downstream.hits.get()
                    + " 次（就是预热那 20 次，之后全命中 Redis）");
            System.out.println("  熔断器状态 = " + service.breakerState());
        }
    }

    // ===================== 实验二：Redis 挂了 + 熔断 + 降级 =====================
    static void experimentTwoRedisDown() throws Exception {
        System.out.println("\n=== 实验二：Redis 挂了 + 降级开关 + 本地缓存顶 ===");
        try (CacheService service = new CacheService(DEAD_REDIS_PORT, DOWNSTREAM_LIMIT)) {
            // ① 先不降级，让请求真的去撞墙：熔断器连续记失败，够 5 次就断开
            for (int i = 0; i < 6; i++) {
                service.get("probe-" + i);
            }
            System.out.println("  撞了 6 次墙后，熔断器状态 = " + service.breakerState()
                    + "（断开后请求直接短路，不再傻等连接超时）");

            // ② 拨下降级开关：核心链路改走「本地缓存 + 下游」，先把能用的功能保住
            service.openDegradeSwitch();
            List<String> hotKeys = hotKeys(20);
            for (String key : hotKeys) {
                service.get(key);   // 预热到本地缓存，每个 key 只打下游一次
            }
            flood(service, repeat(hotKeys, 200));
            System.out.println("  下游被打 " + service.downstream.hits.get()
                    + " 次（6 次探针 + 20 个热点预热），其余请求全被本地缓存接住");
        }
    }

    // ===================== 实验三：闸机限流 =====================
    static void experimentThreeRateLimit() throws Exception {
        System.out.println("\n=== 实验三：同样的穿透洪峰，闸机有没有用 ===");
        List<String> freshKeys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            freshKeys.add("fresh" + i);   // 200 个都没缓存过的 key，全部会穿透到下游
        }
        try (CacheService noLimit = new CacheService(DEAD_REDIS_PORT, 1000);
             CacheService limited = new CacheService(DEAD_REDIS_PORT, DOWNSTREAM_LIMIT)) {
            noLimit.openDegradeSwitch();
            limited.openDegradeSwitch();
            flood(noLimit, freshKeys);
            flood(limited, freshKeys);
            System.out.println("  不限流：同时打下游的峰值 = " + noLimit.downstream.peakConcurrent.get()
                    + " 个线程；限流 " + DOWNSTREAM_LIMIT + " 后 = "
                    + limited.downstream.peakConcurrent.get() + " 个线程（被闸机锁死）");
        }
    }

    // ===================== 实验四：持久化恢复（真实读写 Redis 配置） =====================
    static void experimentFourPersistence() {
        System.out.println("\n=== 实验四：持久化，宕机后能拿回多少 ===");
        try (Jedis jedis = new Jedis("localhost", REDIS_PORT, 200)) {
            jedis.configSet("appendonly", "yes");
            jedis.configSet("appendfsync", "everysec");
            System.out.println("  appendonly  = " + jedis.configGet("appendonly"));
            System.out.println("  appendfsync = " + jedis.configGet("appendfsync"));
            System.out.println("  " + infoLine(jedis.info("persistence"), "aof_last_bgrewrite_status"));
        } catch (RuntimeException redisDown) {
            System.out.println("  Redis 没起，跳过实测（先 docker compose up -d redis）");
        }
        System.out.println("  结论：everysec 每秒落一次盘，宕机最多丢 1 秒数据；纯内存模式一挂全丢。");
    }

    /** 从 INFO 输出里挑一行出来，比如 aof_last_bgrewrite_status:ok */
    static String infoLine(String info, String field) {
        for (String line : info.split("\n")) {
            if (line.startsWith(field + ":")) {
                return line.trim();
            }
        }
        return field + " = 未知";
    }

    // ===================== 小工具 =====================
    static List<String> hotKeys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add("hot:product:" + i);
        }
        return keys;
    }

    /** 把 count 个 key 循环铺成 times 次请求，模拟持续打同一批热点。 */
    static List<String> repeat(List<String> keys, int times) {
        List<String> requests = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            requests.add(keys.get(i % keys.size()));
        }
        return requests;
    }

    /** 让所有线程在发令枪响后同时冲，模拟「热点 key 过期那一瞬间」的并发。 */
    static void flood(CacheService service, List<String> keys) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(keys.size());
        CountDownLatch done = new CountDownLatch(keys.size());
        for (String key : keys) {
            new Thread(() -> {
                ready.countDown();
                try {
                    ready.await();
                    service.get(key);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Redis 挂了怎么办：熔断 + 降级 + 限流 + 持久化 ===");
        experimentOneNormal();
        experimentTwoRedisDown();
        experimentThreeRateLimit();
        experimentFourPersistence();
        System.out.println("\n=== 全部实验结束 ===");
    }
}
