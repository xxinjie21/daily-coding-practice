import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 这个程序在干嘛？
 * 演示「网关层怎么拦住接口重放攻击」——用真 Redis 做 nonce 去重，不再用内存 Map 假装。
 *
 * 重放攻击：攻击者抓到你发的合法请求包，原样再发一遍（比如把一笔支付重复提交）。
 * 网关要在请求进业务系统之前，认出「这是同一张票刷了第二次」。
 *
 * 演示原题目的三板斧：
 *   1) 时间戳：请求必须够新鲜（±5 分钟内），太老的直接扔。
 *   2) Nonce（一次性随机串）：每个请求带一个唯一串，作为「这一张票的唯一编号」。
 *   3) Redis 去重：用一条 SET nonce:xxx 1 EX 360 NX 原子写入——写成功=首次放行，写失败=重放拦截。
 *
 * 关键点：NX 和 EX 必须写在同一条 SET 里。拆成 SETNX + EXPIRE 两步的话，
 * 中间要是崩了就会留下一个永不过期的 key，去重记录越撑越大；写成一条才是原子的。
 *
 * 前置条件：本机 6379 上有 Redis（见 docker-compose.yml），无密码。
 */
public class Demo {

    /** 一个网络请求包（简化版：只留防护要用的字段）。 */
    record Request(long timestamp, String nonce, String payload) {}

    /** 网关的全局过滤器：所有请求都得过这一关。 */
    static class ReplayGuard {
        private final JedisPool pool;        // Jedis 不是线程安全的，用连接池给每个线程发一条连接
        private final long windowSeconds;    // 时间戳允许的最大偏差，比如 300 秒（5 分钟）
        private final long nonceTtlSeconds;  // nonce 在 Redis 里保留多久，略大于窗口，比如 360 秒

        ReplayGuard(JedisPool pool, long windowSeconds, long nonceTtlSeconds) {
            this.pool = pool;
            this.windowSeconds = windowSeconds;
            this.nonceTtlSeconds = nonceTtlSeconds;
        }

        /** 放行返回 true，拦截返回 false。nowMs 是「服务器当前时间」。 */
        boolean allow(Request req, long nowMs) {
            // 第一关：时间戳够新鲜吗？（好比查门票上的使用日期）
            long diff = Math.abs(nowMs - req.timestamp());
            if (diff > windowSeconds * 1000L) {
                System.out.println("    [拦截] 时间戳偏差 " + (diff / 1000) + "s，超过窗口 " + windowSeconds
                        + "s（太老或被改过）");
                return false;
            }
            // 第二关：这个 nonce 之前用过吗？NX=不存在才写，EX=顺带设好过期时间，一条命令保证原子
            String nonceKey = "nonce:" + req.nonce();
            String setResult;
            try (Jedis jedis = pool.getResource()) {
                setResult = jedis.set(nonceKey, "1", SetParams.setParams().nx().ex(nonceTtlSeconds));
            }
            if (setResult == null) {
                // NX 写失败：这个 nonce 已经在 Redis 里了，说明是重放
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

        try (JedisPool pool = new JedisPool("localhost", 6379)) {
            try (Jedis jedis = pool.getResource()) {
                jedis.flushDB();   // 清掉上次跑留下的 nonce，保证演示可重复
            }
            ReplayGuard guard = new ReplayGuard(pool, 300, 360); // 5 分钟窗口，nonce 留 6 分钟

            System.out.println("=== 场景1：一次正常请求 ===");
            Request r1 = new Request(nowMs, "nonce-A1", "pay?order=1001");
            guard.allow(r1, nowMs);

            System.out.println("\n=== 场景2：攻击者原样重放场景1的请求 ===");
            // 攻击者什么都不改，把 r1 整个再发一遍
            guard.allow(r1, nowMs);   // 期望：拦截（nonce 已存在）

            System.out.println("\n=== 场景3：请求包是 10 分钟前抓的（时间戳太老）===");
            Request oldReq = new Request(nowMs - 10L * 60 * 1000, "nonce-B9", "pay?order=1002");
            guard.allow(oldReq, nowMs);   // 期望：拦截（偏差 600s > 300s）

            System.out.println("\n=== 场景4：另一个正常用户，nonce 不同 ===");
            Request r2 = new Request(nowMs, "nonce-C3", "pay?order=1003");
            guard.allow(r2, nowMs);   // 期望：放行（新 nonce）

            System.out.println("\n=== 场景5：同一时刻两个请求 nonce 完全相同（并发重放）===");
            // 两个线程同时发同一个请求，看 SET NX 的原子性能不能兜住：哪怕同一毫秒到达，也只有一个写得进去
            Request dup = new Request(nowMs, "nonce-D0", "pay?order=1004");
            AtomicInteger passCount = new AtomicInteger();
            Thread t1 = new Thread(() -> {
                if (guard.allow(dup, nowMs)) {
                    passCount.incrementAndGet();
                }
            });
            Thread t2 = new Thread(() -> {
                if (guard.allow(dup, nowMs)) {
                    passCount.incrementAndGet();
                }
            });
            t1.start();
            t2.start();
            t1.join();
            t2.join();
            System.out.println("    两个相同 nonce 同时到达，最终放行次数 = " + passCount.get()
                    + "（应为 1，证明 Redis 的原子去重生效）");

            System.out.println("\n提醒：nonce 一定要足够随机（用 UUID / 雪花 ID，别用自增数字），");
            System.out.println("      否则攻击者猜得出下一个 nonce，去重就废了。");
        }
    }
}
