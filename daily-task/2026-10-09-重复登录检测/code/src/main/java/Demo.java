import redis.clients.jedis.Jedis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 演示「5 分钟内重复登录检测」的两种数据结构解法。
 *
 * 方案一（单机）：Map<QQ号, Deque<登录时间戳>>，滑动时间窗口，过期记录从队头踢掉。
 * 方案二（分布式）：Redis Sorted Set，score 存时间戳，ZREMRANGEBYSCORE 清过期、ZCOUNT 数次数。
 *
 * 前置条件：在 code/ 目录执行 `docker compose up -d` 起 Redis（映射到本机 6380 端口）。
 */
public class Demo {

    static final long WINDOW_MS = 5 * 60 * 1000L;      // 判定窗口：5 分钟
    static final long BASE_TIME = 1_730_000_000_000L;  // 固定的「模拟当前时间」，让输出可复现

    // 方案一：每个 QQ 号一个双端队列，队列里只留窗口内的登录时间戳
    static final Map<String, Deque<Long>> userLogins = new HashMap<>();

    // 方案二：同一毫秒内可能登录多次，用自增序号保证 ZSET 的 member 不重名
    static final AtomicLong seq = new AtomicLong();

    public static void main(String[] args) {
        // 事件流：{QQ号, 相对 BASE_TIME 的偏移秒数}
        String[][] events = {
                {"1001", "0"}, {"1001", "60"},   // 间隔 60s < 5min → 第二次算重复
                {"1002", "0"}, {"1002", "400"},  // 间隔 400s > 5min → 第二次不算重复
                {"1003", "0"}, {"1003", "10"}, {"1003", "20"},
        };

        System.out.println("=== 方案一：本地 Map + Deque 滑动窗口 ===");
        for (String[] e : events) {
            long now = BASE_TIME + Long.parseLong(e[1]) * 1000;
            System.out.println("QQ " + e[0] + " 在第 " + e[1] + "s 登录 -> "
                    + (isDuplicateLoginLocal(e[0], now) ? "重复登录！" : "首次登录"));
        }

        System.out.println();
        System.out.println("=== 方案二：Redis Sorted Set ===");
        Jedis jedis = new Jedis("127.0.0.1", 6380);
        try {
            for (String[] e : events) {
                long now = BASE_TIME + Long.parseLong(e[1]) * 1000;
                System.out.println("QQ " + e[0] + " 在第 " + e[1] + "s 登录 -> "
                        + (isDuplicateLoginRedis(jedis, e[0], now) ? "重复登录！" : "首次登录"));
            }
            // 验证清理效果：1003 三次登录都在窗口内应留 3 条，1002 的 0s 记录已被清掉只剩 1 条
            System.out.println("Redis 中 QQ 1003 窗口内记录数 = " + jedis.zcard("login:qq:1003"));
            System.out.println("Redis 中 QQ 1002 窗口内记录数 = " + jedis.zcard("login:qq:1002")
                    + "（旧的 0s 记录已被 ZREMRANGEBYSCORE 清掉）");
        } finally {
            jedis.close();
        }
    }

    /** 方案一：先把过期的登录记录从队头踢掉，再看队列里还有没有「最近的登录」 */
    static boolean isDuplicateLoginLocal(String qq, long now) {
        Deque<Long> times = userLogins.computeIfAbsent(qq, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
        boolean duplicate = !times.isEmpty();
        times.addLast(now);
        return duplicate;
    }

    /** 方案二：Sorted Set 的 score 就是登录时间戳，天然按时间排好序 */
    static boolean isDuplicateLoginRedis(Jedis jedis, String qq, long now) {
        String key = "login:qq:" + qq;
        jedis.zremrangeByScore(key, 0, now - WINDOW_MS);          // 清掉窗口外的旧记录
        jedis.zadd(key, now, now + "-" + seq.incrementAndGet());  // 记录本次登录
        jedis.expire(key, 600);                                   // 兜底：冷用户 key 10 分钟后自动消失
        return jedis.zcount(key, now - WINDOW_MS, now) >= 2;      // 窗口内登录次数 >= 2 即重复
    }
}
