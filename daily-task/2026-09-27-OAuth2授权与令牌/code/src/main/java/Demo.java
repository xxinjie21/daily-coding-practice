// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题：如何设计一个 OAuth2.0 授权服务？Token 怎么发、怎么管才安全？
//
// 生活比喻：去酒店开房。直接给账号密码 = 把家里钥匙交给陌生人；
// OAuth2 的做法 = 前台登记后给你一张房卡（access_token），只能开你这一间房、
// 丢了能挂失、退房自动失效。先用取货凭条（authorization_code）换房卡，
// 换的时候要出示会员卡（client_secret）；房卡快到期用续住卡（refresh_token）换新的。
//
// 这个 Demo 用真实组件把五件事跑一遍：
//   演示一：授权码模式 —— 浏览器全程碰不到真 Token，Token 只在两台服务器之间传
//   演示二：JWT 无状态校验 —— 资源服务用 jjwt 自己验签名就行，不用查库
//   演示三：JWT 的软肋 —— 登出后 Token 还没过期，用 Redis 黑名单（jti）补救
//   演示四：refresh_token 轮换换新 Token —— 用户不用重新登录，偷到也只用得上一次
//   演示五：换个做法 —— 自管理随机 Token 存 Redis，能随时踢人，但每次都得查存储
//
// 前置条件：本机起一个 Redis（见同目录 docker-compose.yml，localhost:6379 无密码）。
// 运行：mvn -q compile exec:java
// ============================================================================

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

public class Demo {

    /** access_token 有效期：15 分钟（越短越安全，靠 refresh_token 续）。 */
    static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60;

    /** refresh_token 有效期：30 天（用户在这期间不用重新登录）。 */
    static final long REFRESH_TOKEN_TTL_SECONDS = 30L * 24 * 60 * 60;

    /** 授权码有效期：5 分钟，而且只能用一次。 */
    static final long AUTH_CODE_TTL_SECONDS = 5 * 60;

    /** 服务端私藏的签名密钥，绝不能泄露（泄露了别人就能自己签 Token）。HS256 要求至少 32 字节。 */
    static final String SIGNING_SECRET = "daily-coding-oauth2-demo-signing-secret-2026";

    /** 已注册的客户端：client_id -> client_secret。真实项目存在数据库里，且 secret 要加密存储。 */
    static final Map<String, String> REGISTERED_CLIENTS = Map.of("web-app", "s3cret-key-do-not-leak");

    /** 假装客户端随请求递过来的 client_secret。真实项目里它绝不能写进日志。 */
    static final String SUBMITTED_CLIENT_SECRET = "s3cret-key-do-not-leak";

    public static void main(String[] args) {
        System.out.println("========== OAuth2 授权服务演示（jjwt 签发 JWT + Redis 管授权码/刷新令牌/黑名单） ==========");

        SecretKey signingKey = Keys.hmacShaKeyFor(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8));
        try (Jedis redis = new Jedis("localhost", 6379)) {
            redis.flushDB(); // 演示用：每次从干净状态开始

            TokenPair tokens = demoAuthorizationCode(redis, signingKey, "1001", "web-app");
            demoBlacklist(redis, signingKey, tokens.accessToken());
            demoRefresh(redis, signingKey, tokens.refreshToken());
            demoOpaqueToken(redis, "1001");
        }
        System.out.println("\n========== 演示结束 ==========");
    }

    // ========================================================================
    // 演示一：授权码模式。为什么非要绕一圈？因为 access_token 全程不经过浏览器，
    // 只在两台服务器之间传递，中间人抓包也抓不到。
    // 顺带演示二：资源服务自己验签名，不用查库。
    // ========================================================================
    private static TokenPair demoAuthorizationCode(Jedis redis, SecretKey signingKey,
                                                  String userId, String clientId) {
        System.out.println("\n========== 演示一：授权码模式，用「临时取货码」换 Token ==========");

        // 第 1 步：用户在授权页登录并点了「同意授权」，授权服务发一张取货码给浏览器
        String authorizationCode = randomToken();
        redis.set(codeKey(authorizationCode), userId + "&" + clientId,
                SetParams.setParams().ex(AUTH_CODE_TTL_SECONDS));
        System.out.println("① 授权服务发给浏览器一张临时取货码 authorization_code = " + authorizationCode);
        System.out.println("   浏览器只拿到这张「取货码」，拿不到真正的 Token。");

        // 第 2 步：客户端服务器拿取货码 + client_secret，直接找授权服务换 Token
        String owner = redis.get(codeKey(authorizationCode));
        redis.del(codeKey(authorizationCode)); // 取货码只能用一次，用完立刻作废
        String codeUserId = owner == null ? "" : owner.split("&")[0];
        String codeClientId = owner == null ? "" : owner.split("&")[1];

        boolean secretMatches = clientId.equals(codeClientId)
                && SUBMITTED_CLIENT_SECRET.equals(REGISTERED_CLIENTS.get(codeClientId));
        System.out.println("② 客户端拿取货码 + client_secret 来换 Token，client_secret 校验："
                + (secretMatches ? "通过" : "失败"));
        System.out.println("   注意：这一步是「服务器对服务器」，浏览器全程看不到 access_token。");

        String accessToken = issueAccessToken(signingKey, codeUserId, "read profile");
        String refreshToken = issueRefreshToken(redis, codeUserId, clientId);
        System.out.println("   access_token  = " + shorten(accessToken));
        System.out.println("   refresh_token = " + shorten(refreshToken));

        // 演示二：资源服务拿到 access_token，自己验签名就够了
        System.out.println("\n========== 演示二：资源服务自己验签名（JWT 无状态，不用查库） ==========");
        printVerifyResult(redis, signingKey, accessToken, "资源服务验签");
        System.out.println("   整个过程没查数据库，这就是 JWT「无状态」的价值（唯一查的是黑名单，见演示三）。");

        return new TokenPair(accessToken, refreshToken);
    }

    // ========================================================================
    // 演示三：JWT 一旦签出去，在过期之前它就是「合法」的。
    // 要支持「点了登出立刻失效」，只能另加黑名单：把 jti 塞进 Redis，
    // 过期时间设成这张卡「剩余的有效期」，到点自动清理，不占内存。
    // ========================================================================
    private static void demoBlacklist(Jedis redis, SecretKey signingKey, String accessToken) {
        System.out.println("\n========== 演示三：JWT 的软肋 —— 用户登出了，Token 却还没过期 ==========");
        printVerifyResult(redis, signingKey, accessToken, "登出前，拿旧 Token 访问资源");

        Claims claims = parseClaims(signingKey, accessToken);
        long remainingSeconds = Math.max(1,
                (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000);
        redis.set(blacklistKey(claims.getId()), "revoked", SetParams.setParams().ex(remainingSeconds));
        System.out.println("   用户点了「登出」，服务端把这张卡的卡号（jti）拉进 Redis 黑名单，"
                + remainingSeconds + " 秒后自动过期。");

        printVerifyResult(redis, signingKey, accessToken, "登出后，拿同一张旧 Token 再访问");
        System.out.println("   → 这就是「JWT 不会自己失效」的补救办法：短期 Token + 黑名单。");
    }

    // ========================================================================
    // 演示四：refresh_token 轮换。用一次就作废、换发新的，
    // 这样万一被偷，攻击者也最多只能用一次。
    // ========================================================================
    private static void demoRefresh(Jedis redis, SecretKey signingKey, String refreshToken) {
        System.out.println("\n========== 演示四：refresh_token 换新 Token，用户不用重新登录 ==========");

        TokenPair newTokens = exchangeRefreshToken(redis, signingKey, refreshToken);
        System.out.println("   换到了新的 access_token  = " + shorten(newTokens.accessToken()));
        System.out.println("   同时 refresh_token 轮换成 = " + shorten(newTokens.refreshToken()));
        System.out.println("   旧的 refresh_token 已经作废，再拿去换："
                + (exchangeRefreshToken(redis, signingKey, refreshToken) == null ? "被拒绝" : "居然还能用"));
        System.out.println("   → 即使 refresh_token 被偷，攻击者也最多只能用一次。");
    }

    // ========================================================================
    // 演示五：另一种做法 —— 自管理随机 Token。
    // 好处是能立刻踢人下线；代价是每次请求都得查一次存储，1k QPS 左右就可能成瓶颈。
    // ========================================================================
    private static void demoOpaqueToken(Jedis redis, String userId) {
        System.out.println("\n========== 演示五：自管理随机 Token（能随时踢人，但每次都要查存储） ==========");

        String opaqueToken = "op-" + randomToken();
        redis.set(opaqueKey(opaqueToken), userId, SetParams.setParams().ex(ACCESS_TOKEN_TTL_SECONDS));
        System.out.println("   签发：一串随机字符 " + shorten(opaqueToken) + "，用户信息不在 Token 里，存在 Redis 中。");
        System.out.println("   校验：每次请求都要查一次 Redis -> 查到 userId = " + redis.get(opaqueKey(opaqueToken)));

        redis.del(opaqueKey(opaqueToken));
        System.out.println("   管理员点了「强制下线」，删掉 Redis 里的记录。");
        System.out.println("   再校验：查到 " + redis.get(opaqueKey(opaqueToken)) + "（null 就是被拒了）");
        System.out.println("   → 好处是能立刻失效；代价是每次请求都得查存储。");
    }

    // ========================================================================
    // 令牌的签发与校验（jjwt 0.12 的新 API）
    // ========================================================================

    /** 签发 access_token。签名就像信封口的火漆印，别人改一个字火漆就裂开。 */
    private static String issueAccessToken(SecretKey signingKey, String userId, String scope) {
        return Jwts.builder()
                .subject(userId)
                .id(UUID.randomUUID().toString()) // jti：这张「房卡」的卡号，拉黑时靠它定位
                .claim("scope", scope)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_TTL_SECONDS * 1000))
                .signWith(signingKey) // 生产上建议换 RS256 非对称签名，资源服务只拿公钥
                .compact();
    }

    /** 签发 refresh_token：一串长随机字符，用户信息存在 Redis 里。 */
    private static String issueRefreshToken(Jedis redis, String userId, String clientId) {
        String refreshToken = randomToken() + randomToken();
        redis.set(refreshKey(refreshToken), userId + "&" + clientId,
                SetParams.setParams().ex(REFRESH_TOKEN_TTL_SECONDS));
        return refreshToken;
    }

    /** 用 refresh_token 换一整套新 Token。旧的立刻作废（轮换）。 */
    private static TokenPair exchangeRefreshToken(Jedis redis, SecretKey signingKey, String refreshToken) {
        String owner = redis.get(refreshKey(refreshToken));
        if (owner == null) {
            return null; // 不存在、已过期，或者已经用过了
        }
        redis.del(refreshKey(refreshToken));

        String[] ownerParts = owner.split("&");
        return new TokenPair(
                issueAccessToken(signingKey, ownerParts[0], "read profile"),
                issueRefreshToken(redis, ownerParts[0], ownerParts[1]));
    }

    /**
     * 校验 access_token：jjwt 自己验签名，不用查库 —— 这就是 JWT 无状态的好处。
     * 唯一的例外是最后那步查黑名单；如果业务能接受「Token 到期才失效」，那步可以删掉。
     */
    private static Claims verifyAccessToken(Jedis redis, SecretKey signingKey, String accessToken) {
        Claims claims = parseClaims(signingKey, accessToken); // 签名不对 / 过期了，这里就会抛异常
        if (redis.exists(blacklistKey(claims.getId()))) {
            throw new IllegalStateException("Token 已被拉黑（用户登出或管理员强制下线）");
        }
        return claims;
    }

    /** 解析并验签。jjwt 0.12 用 parser().verifyWith(key) 这套新写法。 */
    private static Claims parseClaims(SecretKey signingKey, String accessToken) {
        try {
            return Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(accessToken).getPayload();
        } catch (JwtException e) {
            throw new IllegalStateException("Token 校验失败：" + e.getMessage(), e);
        }
    }

    /** 演示用：验过了打印通过，没验过打印被拒的原因。 */
    private static void printVerifyResult(Jedis redis, SecretKey signingKey, String accessToken, String scene) {
        try {
            Claims claims = verifyAccessToken(redis, signingKey, accessToken);
            System.out.println("   " + scene + "：通过（userId=" + claims.getSubject()
                    + "，scope=" + claims.get("scope", String.class) + "）");
        } catch (RuntimeException e) {
            System.out.println("   " + scene + "：拒绝 -> " + e.getMessage());
        }
    }

    // ========================================================================
    // 小工具
    // ========================================================================

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String codeKey(String authorizationCode) {
        return "code:" + authorizationCode;
    }

    private static String refreshKey(String refreshToken) {
        return "refresh:" + refreshToken;
    }

    private static String opaqueKey(String opaqueToken) {
        return "opaque:" + opaqueToken;
    }

    private static String blacklistKey(String jti) {
        return "blacklist:jti:" + jti;
    }

    /** Token 太长了，截短一点方便看。 */
    private static String shorten(String token) {
        return token.length() <= 32 ? token : token.substring(0, 32) + "...(共 " + token.length() + " 字符)";
    }

    /** 一次换 Token 会拿到两样东西，打包成一个 record 返回。 */
    record TokenPair(String accessToken, String refreshToken) {
    }
}
