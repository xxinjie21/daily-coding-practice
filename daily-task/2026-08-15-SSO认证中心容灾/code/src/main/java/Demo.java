import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 这个程序在干嘛？
 * 演示「SSO 认证中心宕机时，已登录用户为什么不受影响」——JWT 用真 jjwt 签发校验，本地花名册用真 Redis。
 *
 * 核心思路（严格按原题）：
 *   1) 登录后发「自包含 Token」（真 JWT，带签名），业务系统用同一把秘钥本地验签，不依赖认证中心；
 *   2) 本地用 Redis 存一份 token -> 用户ID 的映射，TTL 与 Token 一致，中心挂了也能扛一阵；
 *   3) 高可用（VIP / K8s 探活）属于运维侧，代码只点出思路，不展开。
 *
 * 演示对比：中心宕机后，「每次远程验」会被踢下线，而「自包含 Token 本地验 / 本地缓存」仍放行。
 *
 * 前置条件：本机 6379 上有 Redis（见 docker-compose.yml），无密码。
 */
public class Demo {

    // 只有认证中心和各业务系统知道的秘钥。
    // HS256 要求秘钥至少 256 bit（32 字节），太短 jjwt 会直接抛 WeakKeyException，所以这里写长一点。
    static final SecretKey KEY =
            Keys.hmacShaKeyFor("sso-demo-secret-key-2026-please-change-me".getBytes(StandardCharsets.UTF_8));
    // Token 默认有效期：10 秒（演示用，真实场景通常是几十分钟）
    static final long TTL_MILLIS = 10_000L;

    /** 自包含 Token 工具：签发 / 验签 / 取用户ID，全部本地完成，不碰认证中心。 */
    static class JwtUtil {

        static String sign(String userId, long expMillis) {
            return Jwts.builder()
                    .subject(userId)
                    .expiration(new Date(expMillis))
                    .signWith(KEY)
                    .compact();
        }

        /** 验签 + 过期判断：解析失败、签名不对或已过期都会抛 JwtException，catch 住即视为不通过。 */
        static boolean verify(String token) {
            try {
                Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token);
                return true;
            } catch (JwtException | IllegalArgumentException e) {
                return false;
            }
        }

        /** 从 Token 里取出用户 ID（业务系统本地就能认人，不用问中心）。 */
        static String parseUserId(String token) {
            Claims claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
            return claims.getSubject();
        }
    }

    /** 业务系统本地的「已登录花名册」：用真 Redis 存 token -> 用户ID，TTL 与 Token 一致。 */
    static class LocalSessionCache {
        private final Jedis jedis;

        LocalSessionCache(Jedis jedis) {
            this.jedis = jedis;
        }

        void put(String token, String userId, long ttlMillis) {
            // 过期时间交给 Redis 自己管，到期 key 自动消失，不用人工清理
            jedis.set("session:" + token, userId, SetParams.setParams().px(ttlMillis));
        }

        /** 取未过期的会话；不存在或已过期都返回 null（像 Redis 里 key 已失效）。 */
        String getUserId(String token) {
            return jedis.get("session:" + token);
        }
    }

    /** 认证中心：平时在线，宕机后「远程校验」直接抛异常（模拟网络不通）。 */
    static class AuthCenter {
        boolean alive = true;
        int remoteCallCount = 0;   // 统计远程校验被调用次数，用来证明本地路径不依赖它

        /** 登录：中心在线时签发 Token（同时会把凭据写进本地缓存，见 main）。 */
        String login(String userId) {
            if (!alive) {
                throw new IllegalStateException("认证中心宕机，无法登录");
            }
            return JwtUtil.sign(userId, System.currentTimeMillis() + TTL_MILLIS);
        }

        /** 反例：每次请求都远程验一次——中心一挂，全员重登。 */
        boolean validateRemote(String token) {
            remoteCallCount++;
            if (!alive) {
                throw new IllegalStateException("认证中心宕机，远程校验失败");
            }
            return JwtUtil.verify(token);
        }
    }

    public static void main(String[] args) {
        AuthCenter center = new AuthCenter();
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.flushDB();   // 演示用：清掉上次跑留下的会话
            LocalSessionCache cache = new LocalSessionCache(jedis);

            System.out.println("=== 场景一：认证中心正常 ===");
            String token = center.login("u1001");
            cache.put(token, "u1001", TTL_MILLIS);   // 登录同时把凭据写进本地缓存
            System.out.println("远程校验:           " + center.validateRemote(token));
            System.out.println("自包含Token本地验:  " + JwtUtil.verify(token));
            System.out.println("本地缓存校验:       " + (cache.getUserId(token) != null));
            System.out.println("从 Token 本地解析出用户: " + JwtUtil.parseUserId(token));

            System.out.println("\n=== 场景二：认证中心宕机（已登录用户是否受影响？）===");
            center.alive = false;
            // 反例：依赖远程校验 -> 被踢下线
            try {
                center.validateRemote(token);
                System.out.println("远程校验:           仍放行（意外）");
            } catch (IllegalStateException e) {
                System.out.println("远程校验(反例):     被踢下线，需重新登录（体验差）");
            }
            // 推荐：自包含 Token 本地验签，不碰中心 -> 无感
            int callsBefore = center.remoteCallCount;
            boolean selfOk = JwtUtil.verify(token);
            int callsAfter = center.remoteCallCount;
            System.out.println("自包含Token本地验:  " + selfOk + "（用户无感继续访问）");
            System.out.println("  └─ 该步对认证中心的远程调用次数: " + (callsAfter - callsBefore)
                    + "（0 次，完全不依赖中心）");
            // 互补：本地缓存兜底 -> 无感
            System.out.println("本地缓存校验:       " + (cache.getUserId(token) != null) + "（用户无感继续访问）");

            System.out.println("\n=== 场景三：Token 过期了，该重登还是得重登 ===");
            String expiredToken = JwtUtil.sign("u1002", System.currentTimeMillis() - 1_000); // 签发时间在过去
            System.out.println("已过期Token本地校验是否通过: " + JwtUtil.verify(expiredToken)
                    + "（应为 false：过期必须重新登录，本地校验不保永生）");
            System.out.println("（Redis 里的会话带同样的 TTL，到期也自动消失。）");

            System.out.println("\n=== 小结 ===");
            System.out.println("在线验证只发生在登录/刷新那一刻；日常请求靠自包含 Token + 本地校验兜底，");
            System.out.println("所以认证中心挂了，已登录用户完全无感。");
        }
    }
}
