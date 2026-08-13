// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import java.util.*;

/**
 * 这个程序在干嘛？
 * 它模拟「网关层如何拦住接口重放攻击」。
 * 重放攻击：攻击者抓到你发的合法请求包，原样再发一遍（比如把一笔支付重复提交）。
 * 网关要在请求进业务系统之前，认出「这是同一张票刷了第二次」。
 *
 * 演示原题目的三板斧：
 *   1) 时间戳：请求必须够新鲜（±5 分钟内），太老的直接扔。
 *   2) Nonce（一次性随机串）：每个请求带一个唯一串，网关用 Redis 记下来。
 *   3) Redis 去重：同一个 nonce 第二次来，说明是重放，拦截。
 *
 * 真项目里 Redis 是分布式、原子化的；这里用内存 Map + synchronized 模拟，
 * 保证「检查 + 写入」这两步是原子的，效果等同 Redis 的 SETNX。
 */
public class Demo {

    /** 一个网络请求包（简化版：只留防护要用的字段）。record 是 Java 16+ 的不可变数据类。 */
    record Request(long timestamp, String nonce, String payload) {}

    /**
     * 像 Redis 那样的「带过期时间的 KV 存储」。
     * 真项目里就是 Redis；这里用 HashMap 模拟，key -> 过期时间(毫秒)，顺便懒清理过期项。
     */
    static class FakeRedis {
        // key -> 过期时间戳（毫秒）。每次读的时候顺手清掉过期的，模拟 Redis 的 TTL。
        private final Map<String, Long> store = new HashMap<>();

        /**
         * 对应原题目的 redis.set(key, "1", ttl, NX)：
         * 只有 key 不存在（或已过期）时才写入成功并返回 true；已存在就返回 false。
         * 这一步是「原子」的——同一瞬间多个相同请求，只有一个能写成功，其余都算重放。
         * synchronized 是为了让「判断过期 + 写入」合在一起不可被打断，忠实模拟 Redis 的原子性。
         */
        synchronized boolean setIfAbsent(String key, long ttlSeconds, long nowMs) {
            Long expireAt = store.get(key);
            if (expireAt != null && expireAt > nowMs) {
                return false; // 还没过期，说明之前有人用过这个 nonce
            }
            store.put(key, nowMs + ttlSeconds * 1000L); // 写入并标记过期时间
            return true;
        }
    }

    /** 网关的全局过滤器：所有请求都得过这一关。 */
    static class ReplayGuard {
        private final FakeRedis redis;
        private final long windowSeconds;     // 时间戳允许的最大偏差，比如 300 秒（5 分钟）
        private final long nonceTtlSeconds;   // nonce 在 Redis 里保留多久，略大于窗口，比如 360 秒（6 分钟）

        ReplayGuard(FakeRedis redis, long windowSeconds, long nonceTtlSeconds) {
            this.redis = redis;
            this.windowSeconds = windowSeconds;
            this.nonceTtlSeconds = nonceTtlSeconds;
        }

        /** 放行返回 true，拦截返回 false。nowMs 是「服务器当前时间」。 */
        boolean allow(Request req, long nowMs) {
            // 第一关：时间戳够新鲜吗？（好比查门票上的使用日期）
            long diff = Math.abs(nowMs - req.timestamp());
            if (diff > windowSeconds * 1000L) {
                System.out.println("    [拦截] 时间戳偏差 " + (diff / 1000) + "s，超过窗口 " + windowSeconds + "s（太老或被改过）");
                return false;
            }
            // 第二关：这个 nonce 之前用过吗？（好比查这张票编号有没有核销过）
            String nonceKey = "nonce:" + req.nonce();
            boolean firstTime = redis.setIfAbsent(nonceKey, nonceTtlSeconds, nowMs);
            if (!firstTime) {
                System.out.println("    [拦截] nonce=" + req.nonce() + " 已在 Redis 中存在 -> 判定为重放");
                return false;
            }
            System.out.println("    [放行] 新鲜且首次出现，进入业务系统");
            return true;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 服务器「现在」的时刻，演示里用一个固定值当基准时间
        long nowMs = 1_700_000_000_000L;

        FakeRedis redis = new FakeRedis();
        ReplayGuard guard = new ReplayGuard(redis, 300, 360); // 5 分钟窗口，nonce 留 6 分钟

        System.out.println("=== 场景1：一次正常请求 ===");
        Request r1 = new Request(nowMs, "nonce-A1", "pay?order=1001");
        guard.allow(r1, nowMs);

        System.out.println("\n=== 场景2：攻击者原样重放场景1的请求 ===");
        // 攻击者什么都不改，把 r1 整个再发一遍
        guard.allow(r1, nowMs); // 期望：拦截（nonce 已存在）

        System.out.println("\n=== 场景3：请求包是 10 分钟前抓的（时间戳太老）===");
        Request oldReq = new Request(nowMs - 10L * 60 * 1000, "nonce-B9", "pay?order=1002");
        guard.allow(oldReq, nowMs); // 期望：拦截（时间戳偏差 600s > 300s）

        System.out.println("\n=== 场景4：另一个正常用户，nonce 不同 ===");
        Request r2 = new Request(nowMs, "nonce-C3", "pay?order=1003");
        guard.allow(r2, nowMs); // 期望：放行（新 nonce）

        System.out.println("\n=== 场景5：同一时刻两个请求 nonce 完全相同（并发重放）===");
        // 用两个线程同时发同一个请求，看 SETNX 的原子性保不保得住
        Request dup = new Request(nowMs, "nonce-D0", "pay?order=1004");
        final int[] passCount = {0};
        Thread t1 = new Thread(() -> { if (guard.allow(dup, nowMs)) passCount[0]++; });
        Thread t2 = new Thread(() -> { if (guard.allow(dup, nowMs)) passCount[0]++; });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        System.out.println("    两个相同 nonce 同时到达，最终放行次数 = " + passCount[0] + "（应为 1，证明原子去重生效）");
    }
}
