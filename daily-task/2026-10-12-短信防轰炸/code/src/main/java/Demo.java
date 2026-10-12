import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

/**
 * 短信验证码「防轰炸」演示 —— 真实 Redis（Jedis 5.2.0），没有任何内存对象仿真。
 *
 * 前置条件：在本文件所在的 code/ 目录执行 `docker compose up -d` 起一个 Redis（映射 6380 -> 容器 6379）。
 *
 * 请求进来后按四道闸门依次校验，任何一道不过就拒掉：
 *   闸门1  IP 黑名单      —— 已被封的 IP 连后面的检查都不用做；
 *   闸门2  图形验证码票据 —— 必须先过滑块/图形验证码拿到一张「一次性票据」，且只能消费一次（GETDEL 原子）；
 *   闸门3  手机号三维频控 —— 1 分钟 1 次 / 1 小时 5 次 / 1 天 10 次；
 *   闸门4  IP 计数封禁    —— 同一 IP 10 分钟内发超 5 次，直接封 10 分钟。
 *
 * 闸门3 的「计数 + 首次设过期」给了两份实现并交叉验证：
 *   withinLimit      —— Java 版，INCR 之后再 EXPIRE，两条命令；
 *   withinLimitByLua —— 真实 Lua 版，INCR + 首次 EXPIRE 放进一条脚本，Redis 单线程执行天然原子。
 */
public class Demo {

    static final String REDIS_HOST = "127.0.0.1";
    static final int REDIS_PORT = 6380;

    /** 校验结果：通过与否 + 给前端的说明。 */
    record Result(boolean passed, String message) {
    }

    public static void main(String[] args) {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.flushDB();                       // 演示前清空，保证每次跑出来的结果都一样

            runSamePhoneBurst(jedis);              // 场景1：同一手机号 1 分钟内连点
            runWithoutCaptcha(jedis);              // 场景2：跳过图形验证码直接发
            runSameIpBurst(jedis);                 // 场景3：同一 IP 换手机号刷
            runCrossCheck(jedis);                  // 场景4：Java 版与 Lua 版计数交叉验证
        }
    }

    /** 场景1：同一手机号 1 分钟内连点 3 次（每次都规规矩矩过了图形验证码）。 */
    static void runSamePhoneBurst(Jedis jedis) {
        System.out.println("== 场景1：同一手机号 1 分钟内连点 3 次 ==");
        String phone = "13800000001";
        String ip = "10.0.0.1";
        for (int i = 1; i <= 3; i++) {
            issueCaptchaTicket(jedis, phone);
            System.out.println("  第" + i + "次 -> " + requestSms(jedis, phone, ip).message());
        }
    }

    /** 场景2：攻击脚本没过图形验证码，直接调发送接口 —— 在闸门2 被拦。 */
    static void runWithoutCaptcha(Jedis jedis) {
        System.out.println("== 场景2：跳过图形验证码直接发 ==");
        System.out.println("  -> " + requestSms(jedis, "13800000002", "10.0.0.2").message());
    }

    /** 场景3：同一 IP 换 6 个手机号刷（每个号都过了验证码）—— 第 6 次触发 IP 封禁。 */
    static void runSameIpBurst(Jedis jedis) {
        System.out.println("== 场景3：同一 IP 换 6 个手机号刷 ==");
        String ip = "10.0.0.3";
        for (int i = 1; i <= 6; i++) {
            String phone = "1390000000" + i;
            issueCaptchaTicket(jedis, phone);
            System.out.println("  第" + i + "个号 -> " + requestSms(jedis, phone, ip).message());
        }
        System.out.println("  封禁标记剩余 TTL = " + jedis.ttl(banKey(ip)) + " 秒");

        String anotherPhone = "139000000099";
        issueCaptchaTicket(jedis, anotherPhone);
        System.out.println("  封禁后再发 -> " + requestSms(jedis, anotherPhone, ip).message());
    }

    /** 场景4：同一套规则，Java 版与 Lua 版跑出来的计数和 TTL 必须完全一致。 */
    static void runCrossCheck(Jedis jedis) {
        System.out.println("== 场景4：Java 版 vs Lua 版计数交叉验证（阈值 5 次 / 1 小时）==");
        String javaKey = "sms:phone:hour:cross:java";
        String luaKey = "sms:phone:hour:cross:lua";
        for (int i = 1; i <= 7; i++) {
            boolean javaPassed = withinLimit(jedis, javaKey, 5, 3600);
            boolean luaPassed = withinLimitByLua(jedis, luaKey, 5, 3600);
            System.out.println("  第" + i + "次  java=" + javaPassed + "  lua=" + luaPassed
                    + "  java计数=" + jedis.get(javaKey) + "  lua计数=" + jedis.get(luaKey)
                    + "  java的TTL=" + jedis.ttl(javaKey));
        }
    }

    /**
     * 发送验证码的主流程：四道闸门顺序校验，全过了才算「真发出去」。
     */
    static Result requestSms(Jedis jedis, String phone, String ip) {
        // 闸门1：IP 是否在黑名单里
        if (jedis.exists(banKey(ip))) {
            return new Result(false, "IP " + ip + " 已被封禁，请 10 分钟后再试");
        }

        // 闸门2：票据必须存在，且用 GETDEL 一次性消费（取和删是一步，脚本没法拿同一张票据重放）
        String ticketKey = "sms:captcha:" + phone;
        if (!"pass".equals(jedis.getDel(ticketKey))) {
            return new Result(false, "请先完成图形验证码");
        }

        // 闸门3：手机号维度的三级频控
        if (!withinLimit(jedis, "sms:phone:minute:" + phone, 1, 60)) {
            return new Result(false, "同一手机号 1 分钟内只能发 1 次");
        }
        if (!withinLimit(jedis, "sms:phone:hour:" + phone, 5, 3600)) {
            return new Result(false, "同一手机号 1 小时内最多 5 次");
        }
        if (!withinLimit(jedis, "sms:phone:day:" + phone, 10, 86400)) {
            return new Result(false, "同一手机号 1 天内最多 10 次");
        }

        // 闸门4：IP 维度计数，超了就拉黑
        long ipCount = countOnce(jedis, "sms:ip:count:" + ip, 600);
        if (ipCount > 5) {
            jedis.set(banKey(ip), "1", SetParams.setParams().ex(600));
            return new Result(false, "IP " + ip + " 请求过于频繁，已封禁 10 分钟");
        }
        return new Result(true, "已向 " + phone + " 发送验证码（该 IP 本次窗口第 " + ipCount + " 次）");
    }

    /**
     * Java 版频控：INCR 之后判断是不是「本窗口第一次」，是就设过期。
     * 只有第一次才 EXPIRE，所以窗口 TTL 不会被后续请求不断刷新（否则计数窗口会无限延长）。
     */
    static boolean withinLimit(Jedis jedis, String key, int limit, int ttlSeconds) {
        return countOnce(jedis, key, ttlSeconds) <= limit;
    }

    /** INCR + 首次设过期，返回自增后的计数值。 */
    static long countOnce(Jedis jedis, String key, int ttlSeconds) {
        long count = jedis.incr(key);
        if (count == 1) {
            jedis.expire(key, ttlSeconds);
        }
        return count;
    }

    /**
     * Lua 版频控：INCR 与首次 EXPIRE 放在同一条脚本里，Redis 单线程执行，不会出现
     * 「INCR 成功了但 EXPIRE 没执行」导致 key 永不过期的尴尬（Java 版两条命令之间存在这个窗口）。
     */
    static boolean withinLimitByLua(Jedis jedis, String key, int limit, int ttlSeconds) {
        String script = """
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return count
                """;
        long count = (Long) jedis.eval(script, 1, key, String.valueOf(ttlSeconds));
        return count <= limit;
    }

    /** 模拟用户先通过了图形验证码，服务端下发一张 5 分钟有效的一次性票据。 */
    static void issueCaptchaTicket(Jedis jedis, String phone) {
        jedis.set("sms:captcha:" + phone, "pass", SetParams.setParams().ex(300));
    }

    static String banKey(String ip) {
        return "sms:ban:ip:" + ip;
    }
}
