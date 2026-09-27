// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「项目里用了分布式锁，加锁后并发度不就降低了吗？」。
//   我们连真实的 Redis（用 Jedis 客户端），演示三种做法，看并发度怎么从低到高：
//     ① 全局锁   —— 一把锁拦住所有人：最安全，但大家排队
//     ② 分段锁   —— 按商品 ID 分成 16 段，各排各的队：并发翻几倍
//     ③ 无锁原子 —— 用 Lua 脚本在 Redis 里一步扣完库存：压根不用锁
//
// 前置条件：本机要有 Redis。在本目录执行 `docker compose up -d` 就能起一个。
//
// 生活比喻：
//   全局锁   = 全小区只有 1 个快递柜，谁取件都得等前一个人
//   分段锁   = 每栋楼各 1 个柜，1 栋楼取件不影响 2 栋楼
//   无锁原子 = 快递员直接把件塞进你家信箱，一条动作完成，压根不用柜

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    static final String REDIS_HOST = "localhost";
    static final int REDIS_PORT = 6379;

    /** 分段锁分成几段。段数越多，不同商品撞到同一段的概率越低。 */
    static final int SEGMENT_COUNT = 16;

    /** 锁的自动过期时间（毫秒）。持锁的人要是宕机了，靠它把锁放出来，避免死锁。 */
    static final long LOCK_TTL_MILLIS = 30_000;

    /** 释放锁的 Lua 脚本：只有「锁还是我加的」才允许删，防止误删别人的锁。 */
    static final String UNLOCK_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

    /** 扣库存的 Lua 脚本：判断和扣减在 Redis 里一气呵成，中间不会被打断。 */
    static final String DEDUCT_STOCK_SCRIPT = """
            local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
            if stock <= 0 then
                return -1
            end
            return redis.call('decr', KEYS[1])
            """;

    public static void main(String[] args) throws InterruptedException {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.flushDB();
            demoGlobalLock(jedis);
            demoSegmentedLock(jedis);
            demoLockFreeDeduct(jedis);
        }
    }

    // ==================================================================
    // ① 全局锁：一把锁拦住所有人
    // ==================================================================
    static void demoGlobalLock(Jedis jedis) throws InterruptedException {
        System.out.println("========== ① 全局锁：一把锁拦住所有人 ==========");
        jedis.set("stock:apple", "1");   // 苹果只剩 1 件

        int buyerCount = 5;
        CountDownLatch allDone = new CountDownLatch(buyerCount);
        AtomicInteger successCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);

        for (int i = 1; i <= buyerCount; i++) {
            // requestId 用来标记「这把锁是我加的」，释放时靠它防止误删别人的锁
            String requestId = "buyer-" + i + "-" + UUID.randomUUID();
            pool.submit(() -> {
                try (Jedis workerJedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                    // 自旋抢锁：抢不到就歇 50ms 再试 —— 这就是「排队」
                    if (!tryLockWithRetry(workerJedis, "lock:stock:apple", requestId, 20)) {
                        return;
                    }
                    if (Integer.parseInt(workerJedis.get("stock:apple")) > 0) {
                        workerJedis.decr("stock:apple");
                        successCount.incrementAndGet();
                    }
                    unlock(workerJedis, "lock:stock:apple", requestId);
                } finally {
                    allDone.countDown();
                }
            });
        }
        allDone.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("5 个人抢 1 件苹果，成功买到的人：" + successCount.get() + " 个");
        System.out.println("→ 全局锁保证不超卖，代价是 5 个人全得排队。");
        System.out.println();
    }

    // ==================================================================
    // ② 分段锁：按商品 ID 分桶，不同商品落到不同段，互不阻塞
    // ==================================================================
    static void demoSegmentedLock(Jedis jedis) {
        System.out.println("========== ② 分段锁：按商品 ID 分 16 段 ==========");
        for (String product : List.of("apple", "banana", "cherry", "phone", "laptop")) {
            System.out.println("商品 " + product + " → " + segmentLockKey(product));
        }
        System.out.println("→ 两个商品的段号只要不同，就能同时扣库存，谁也不用等谁。");
        System.out.println();
    }

    // ==================================================================
    // ③ 无锁原子：一条 Lua 命令扣完，压根不加锁
    // ==================================================================
    static void demoLockFreeDeduct(Jedis jedis) throws InterruptedException {
        System.out.println("========== ③ 无锁原子：一条 Lua 命令扣完，压根不加锁 ==========");
        jedis.set("stock:flash", "5");   // 秒杀库存 5 件

        int threadCount = 10;
        CountDownLatch allDone = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try (Jedis workerJedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                    if (deductStock(workerJedis, "stock:flash") >= 0) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    allDone.countDown();
                }
            });
        }
        allDone.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("10 个线程抢 5 件秒杀库存，成功扣减次数：" + successCount.get());
        System.out.println("剩余库存：" + jedis.get("stock:flash"));
        System.out.println("→ 没加锁也没超卖：判断和扣减在 Redis 里是一个不可打断的原子动作。");
    }

    // ==================================================================
    // 下面都是 Redis 操作的小封装
    // ==================================================================

    /**
     * 尝试加锁：SET key value NX PX ttl。
     * NX = 只有 key 不存在时才设置成功，这就是「互斥」；
     * PX = 带上过期时间，防止持锁的人宕机后锁永远不释放。
     */
    static boolean tryLock(Jedis jedis, String lockKey, String requestId, long ttlMillis) {
        String result = jedis.set(lockKey, requestId, SetParams.setParams().nx().px(ttlMillis));
        return "OK".equals(result);
    }

    /** 抢不到就歇一会儿再试，最多试 maxAttempts 次。 */
    static boolean tryLockWithRetry(Jedis jedis, String lockKey, String requestId, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (tryLock(jedis, lockKey, requestId, LOCK_TTL_MILLIS)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 释放锁。走 Lua 脚本，保证「比对 requestId」和「删除」是一个原子动作。 */
    static boolean unlock(Jedis jedis, String lockKey, String requestId) {
        Object result = jedis.eval(UNLOCK_SCRIPT, List.of(lockKey), List.of(requestId));
        return (Long) result > 0;
    }

    /** 扣一件库存：返回扣减后的库存，库存不足返回 -1。 */
    static long deductStock(Jedis jedis, String stockKey) {
        Object result = jedis.eval(DEDUCT_STOCK_SCRIPT, List.of(stockKey), List.of());
        return (Long) result;
    }

    /** 按商品 ID 算出它该用哪一把段锁：同一商品永远落到同一段。 */
    static String segmentLockKey(String productId) {
        int segment = Math.floorMod(productId.hashCode(), SEGMENT_COUNT);
        return "lock:stock:segment:" + segment;
    }
}
