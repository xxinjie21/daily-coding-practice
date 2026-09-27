// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「网站从一台服务器扩成好几台后，用户在 A 机登录成功，下次请求被分到 B 机，
//   B 不认识他、提示没登录，怎么让所有机器都认得同一个已登录的用户」。三种思路各演示一遍：
//     ① Redis 分布式会话（推荐）—— 登录档案从「每台机自己的内存」搬到大家共用的 Redis，
//                                   谁拿着 sessionId 都能取到同一份（真实 Jedis 调用）
//     ② JWT 无状态令牌           —— 服务端不存任何东西，用 jjwt 签发/验签，还演示篡改后验签失败
//     ③ IP Hash 粘性会话         —— 按 IP 固定分机，会话放本机内存；机器一挂会话就丢
//
// 前置条件：本机要有 Redis。在本目录执行 `docker compose up -d` 就能起一个。
//
// 生活比喻：登录状态像游乐园手环。单机时代只有一个检票口，手环记在它的小本子上；
//          现在开了好几个检票口，你在 1 号口登记，走到 3 号口它当然说「没见过你」。
//          Redis 就是那个所有检票口共用的登记台。

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

public class Demo {

    static final String REDIS_HOST = "localhost";
    static final int REDIS_PORT = 6379;

    /** 会话 30 分钟不活跃就过期，活跃一次就续期。 */
    static final int SESSION_TTL_SECONDS = 30 * 60;
    static final String SESSION_KEY_PREFIX = "session:";

    /** JWT 签名密钥：只有服务端知道。多台机器共享同一把即可，无需任何存储。 */
    static final SecretKey JWT_KEY = Keys.hmacShaKeyFor(
            "session-demo-secret-key-must-be-32-bytes+".getBytes(StandardCharsets.UTF_8));

    public static void main(String[] args) {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.flushDB();
            demoRedisSession(jedis);
            demoJwt();
            demoIpHash();
        }
    }

    // ==================================================================
    // 方案一：Redis 分布式会话。两台服务器都不存登录信息，读写的是同一个 Redis。
    // ==================================================================
    static void demoRedisSession(Jedis jedis) {
        System.out.println("======== 方案一：Redis 分布式会话（推荐） ========");

        // 用户在【服务器A】登录：生成 sessionId，把档案写进共用的 Redis，再把钥匙发给浏览器
        String sessionId = UUID.randomUUID().toString();
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        jedis.set(sessionKey, "张三|admin", SetParams.setParams().ex(SESSION_TTL_SECONDS));
        System.out.println("张三在【服务器A】登录，写 Redis：" + sessionKey.substring(0, 16)
                + "... → 张三|admin（TTL " + jedis.ttl(sessionKey) + " 秒）");

        // 下一次请求被负载均衡分给了【服务器B】：B 只是拿同一把钥匙去 Redis 查，自己什么都没存
        String profile = jedis.get(sessionKey);
        System.out.println("请求落到【服务器B】，拿同一把钥匙查 Redis → "
                + (profile == null ? "查不到（不该发生）" : "认得！用户档案：" + profile));

        // 踢人下线：删掉 Redis 里这个 key 就完事，所有机器立刻都不认他
        jedis.del(sessionKey);
        System.out.println("张三被踢下线（删掉 Redis 的 key）");
        System.out.println("再用旧钥匙在【服务器B】查 → "
                + (jedis.get(sessionKey) == null ? "已失效，提示未登录 ✅" : "居然还在（不该发生）"));
        System.out.println();
    }

    // ==================================================================
    // 方案二：JWT 无状态令牌。服务端不存档案，把用户信息签进令牌让客户端自己带。
    // ==================================================================
    static void demoJwt() {
        System.out.println("======== 方案二：JWT 无状态令牌 ========");

        // 签发：用户信息 + 防伪签名打包成一张令牌
        String token = Jwts.builder()
                .subject("1001")
                .claim("userName", "李四")
                .claim("role", "user")
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(JWT_KEY)
                .compact();
        System.out.println("李四登录成功，服务端签发 JWT：" + shorten(token));

        // 任意一台服务器收到请求，用同一把密钥验签就能识别用户，全程不查任何存储
        Claims claims = Jwts.parser().verifyWith(JWT_KEY).build()
                .parseSignedClaims(token).getPayload();
        System.out.println("任意服务器验签通过 → 用户 " + claims.getSubject()
                + "（" + claims.get("userName") + "，角色 " + claims.get("role") + "）");

        // 坏人偷偷把载荷里的 role 改成 admin，但他没有密钥、算不出新签名 —— 验签立刻识破
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"1001\",\"userName\":\"李四\",\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        String forgedToken = parts[0] + "." + forgedPayload + "." + parts[2];
        try {
            Jwts.parser().verifyWith(JWT_KEY).build().parseSignedClaims(forgedToken);
            System.out.println("篡改 role 后再验签 → 居然通过（不该发生）");
        } catch (JwtException e) {
            System.out.println("篡改 role 后再验签 → 签名对不上，拒绝 ✅（" + e.getClass().getSimpleName() + "）");
        }
        System.out.println();
    }

    // ==================================================================
    // 方案三：IP Hash 粘性会话。负载均衡按 IP 哈希固定分机，会话放本机内存就够，
    // 代价是那台机器一挂，它上面的会话全丢 —— 只能当救急方案。
    // ==================================================================
    static void demoIpHash() {
        System.out.println("======== 方案三：IP Hash 粘性会话（临时方案） ========");
        String[] servers = {"服务器0", "服务器1", "服务器2"};
        String clientIp = "203.0.113.55";

        int index = pickServerByIpHash(clientIp, servers.length);
        System.out.println("客户端 IP=" + clientIp + " 被固定分配到 → " + servers[index]);
        System.out.println("同一 IP 再次请求 → " + servers[pickServerByIpHash(clientIp, servers.length)]
                + "（始终不变，所以会话能命中同一台机器）");

        int afterCrash = pickServerByIpHash(clientIp, servers.length - 1);
        System.out.println("若该机器宕机（可用机器减为 2 台）→ 同一 IP 改分到 " + servers[afterCrash]
                + "，旧机器内存里的会话丢失 ⚠️");
    }

    /** IP Hash：同一 IP 的哈希对机器数取模，永远落在同一台机器上。 */
    static int pickServerByIpHash(String clientIp, int serverCount) {
        return Math.abs(clientIp.hashCode()) % serverCount;
    }

    /** 令牌很长，打印时截短，方便看。 */
    static String shorten(String text) {
        return text.length() <= 16 ? text : text.substring(0, 16) + "...";
    }
}
