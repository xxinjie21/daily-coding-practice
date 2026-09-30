import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 演示：一条 1 秒的请求，怎么压到毫秒级。
 *
 * 场景：商品详情接口要凑齐 4 份数据才能返回 —— 商品信息、库存、价格、推荐列表。
 * 每份数据都要去问一个「很慢的下游」（真实世界是查库或调 RPC，这里 sleep 250ms 代表它）。
 *
 * 演示三条路线，耗时从 1 秒一路降到 1 毫秒以内：
 *   1) loadSerial   —— 老实串行取 4 次：250 * 4 ≈ 1000ms
 *   2) loadParallel —— CompletableFuture 并行取：≈ 250ms（计算层优化）
 *   3) loadCached   —— Caffeine 本地缓存 -> Redis 缓存 -> 回源，逐级回填（存储层优化）
 *                      冷缓存 ≈ 250ms，之后命中本地缓存只要几微秒
 *
 * 前置条件：先起 Redis（二级缓存）
 *   docker compose -f code/docker-compose.yml up -d
 */
public class Demo {

    /** 一级缓存：进程内，命中只要微秒级，但只在本机有效、重启就没了。 */
    static final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .build();

    /** 二级缓存：Redis，跨机器共享，命中 1~2ms。端口用 6380 避开本机已占用的 6379。 */
    static final JedisPool redis = new JedisPool("127.0.0.1", 6380);

    /** 模拟慢下游：sleep 250ms 代表「查一次数据库 / 调一次远程接口」的耗时。 */
    static String slowSource(String key) {
        sleep(250);
        return key + "-value";
    }

    /** 路线 1：串行取 4 份数据 —— 什么都没优化，四次耗时直接相加。 */
    static List<String> loadSerial(List<String> keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            result.add(slowSource(key));
        }
        return result;
    }

    /**
     * 路线 2：4 份数据互不依赖，丢给线程池并行取。
     * 总耗时约等于「最慢的那一个」，而不是四个相加。
     */
    static List<String> loadParallel(List<String> keys) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(keys.size());
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (String key : keys) {
                futures.add(CompletableFuture.supplyAsync(() -> slowSource(key), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> result = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                result.add(future.get());
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 路线 3：三级取数，越靠前越快。
     * 本地缓存命中就不碰网络；本地没有才问 Redis；Redis 也没有才回源，并逐级回填。
     */
    static String loadCached(String key) {
        String value = localCache.getIfPresent(key);
        if (value != null) {
            return value;                       // 一级命中：微秒级，最快
        }
        try (Jedis jedis = redis.getResource()) {
            value = jedis.get(key);             // 二级命中：毫秒级
            if (value == null) {
                value = slowSource(key);        // 三级：回源，最慢的那一步
                jedis.setex(key, 30, value);    // 回填 Redis，下次别人也快
            }
        }
        localCache.put(key, value);             // 回填本地，下次连 Redis 都不用问
        return value;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> keys = List.of("product", "stock", "price", "recommend");

        // 清掉上次运行残留的 Redis 缓存，保证「冷缓存」从零开始
        try (Jedis jedis = redis.getResource()) {
            for (String key : keys) {
                jedis.del(key);
            }
        }

        System.out.println("=== 路线1：串行调用 4 个慢下游 ===");
        long start = System.currentTimeMillis();
        loadSerial(keys);
        System.out.println("耗时 " + (System.currentTimeMillis() - start) + " ms");

        System.out.println("=== 路线2：CompletableFuture 并行调用 ===");
        start = System.currentTimeMillis();
        loadParallel(keys);
        System.out.println("耗时 " + (System.currentTimeMillis() - start) + " ms");

        System.out.println("=== 路线3：本地缓存 + Redis 三级取数 ===");
        start = System.currentTimeMillis();
        for (String key : keys) {
            loadCached(key);                    // 冷缓存：本地、Redis 都没有，只能回源
        }
        System.out.println("冷缓存一轮耗时 " + (System.currentTimeMillis() - start) + " ms");

        int rounds = 1000;
        long nanos = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            for (String key : keys) {
                loadCached(key);                // 热缓存：全部命中一级，只做一次内存查表
            }
        }
        nanos = System.nanoTime() - nanos;
        System.out.println("热缓存 " + rounds + " 轮，平均每轮 " + (nanos / rounds / 1000) + " us");

        redis.close();
    }
}
