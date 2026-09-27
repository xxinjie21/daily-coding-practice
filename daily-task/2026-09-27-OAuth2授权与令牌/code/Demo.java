// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   题目是「如何设计一个 OAuth2.0 授权服务？Token 如何管理？」。
//   我们用纯 Java（不引任何第三方库）把原题的核心流程串成一个能跑起来的小演示：
//
//     演示一：授权码模式 —— 用户先拿一张「临时取货码」，浏览器全程碰不到真 Token
//     演示二：JWT 无状态校验 —— 资源服务自己验签名就行，不用查数据库
//     演示三：JWT 的软肋 —— 用户登出后 Token 还没过期，用黑名单补救
//     演示四：refresh_token 换新 Token —— 用户不用重新登录
//     演示五：换个做法 —— 自管理随机 Token，能随时踢人下线，但每次都得查存储
//
// 说明：真实项目用 Spring Authorization Server 搭骨架、签名用 RS256、存储用 Redis。
//       这里为了让 Demo 能独立跑起来，用 HMAC-SHA256 签名、用内存 Map 模拟 Redis，
//       原理是完全一样的。

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Demo {

    // ==================================================================
    // 一、极简版「Redis」：用来放授权码、refresh_token、Token 黑名单
    //
    //     真实项目里这是个 Redis。这里用「带过期时间的 Map」来模拟，
    //     因为「到点自动消失」正是 Redis 最常用的能力。
    // ==================================================================
    static class SimpleStore {

        // 存的值
        private final Map<String, String> valueMap = new ConcurrentHashMap<>();
        // 每个 key 的过期时刻（毫秒时间戳）
        private final Map<String, Long> expireAtMap = new ConcurrentHashMap<>();

        /** 存一个值，并带上存活时间 ttlMillis（毫秒）。 */
        void set(String key, String value, long ttlMillis) {
            valueMap.put(key, value);
            expireAtMap.put(key, System.currentTimeMillis() + ttlMillis);
        }

        /** 取值：已经过期的就当作不存在（顺手删掉，模拟 Redis 的自动清理）。 */
        String get(String key) {
            Long expireAt = expireAtMap.get(key);
            if (expireAt == null) {
                return null;
            }
            if (System.currentTimeMillis() > expireAt) {
                delete(key);
                return null;
            }
            return valueMap.get(key);
        }

        void delete(String key) {
            valueMap.remove(key);
            expireAtMap.remove(key);
        }

        boolean exists(String key) {
            return get(key) != null;
        }
    }

    // ==================================================================
    // 二、令牌服务：负责「签发 Token」和「校验 Token」
    //
    //     JWT 长这样：  头部.载荷.签名
    //     签名就像给信封口盖的火漆印 —— 别人改动里面一个字，火漆立刻裂开，
    //     所以资源服务不用问任何人，自己看火漆就知道这封信有没有被伪造。
    // ==================================================================
    static class TokenService {

        // 服务器私藏的钥匙，绝不能泄露给客户端（泄露了别人就能自己签 Token）
        private final byte[] secretKey;
        // 用来放黑名单（模拟 Redis）
        private final SimpleStore store;
        // access_token 的有效期（秒）
        private final long accessTokenTtlSeconds;

        TokenService(byte[] secretKey, SimpleStore store, long accessTokenTtlSeconds) {
            this.secretKey = secretKey;
            this.store = store;
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        // ---------------- 签发 ----------------

        /** 签发一个 access_token（JWT 格式）。 */
        String issueAccessToken(String userId, String scope) {
            // 过期时刻：当前秒数 + 有效期
            long expireAtSeconds = System.currentTimeMillis() / 1000 + accessTokenTtlSeconds;
            // jti 是这张「房卡」的卡号，将来要拉黑时靠它找到具体是哪一张
            String jti = UUID.randomUUID().toString();

            String header = "alg=HS256&typ=JWT";
            // 真实 JWT 这里是一段 JSON；为了不引第三方 JSON 库，这里用 k=v 拼，
            // 内容含义完全一样：谁、有什么权限、什么时候过期、卡号是多少
            String payload = "userId=" + userId
                    + "&scope=" + scope
                    + "&exp=" + expireAtSeconds
                    + "&jti=" + jti;

            String signingInput = base64UrlEncode(header) + "." + base64UrlEncode(payload);
            String signature = hmacSha256(signingInput);
            return signingInput + "." + signature;
        }

        /** 签发 refresh_token：一串长随机字符，用户信息存在服务端存储里。 */
        String issueRefreshToken(String userId, String clientId) {
            String refreshToken = randomHex() + randomHex();
            // 记下「这个续住卡属于谁、是哪个客户端申请的」
            store.set("refresh:" + refreshToken, userId + "&" + clientId, REFRESH_TOKEN_TTL_MILLIS);
            return refreshToken;
        }

        /** 签发自管理随机 Token（另一种做法，见演示五）。 */
        String issueOpaqueToken(String userId) {
            String opaqueToken = "op-" + randomHex();
            store.set("opaque:" + opaqueToken, userId, accessTokenTtlSeconds * 1000);
            return opaqueToken;
        }

        // ---------------- 校验 ----------------

        /**
         * 校验 access_token，通过就返回里面的信息，不通过返回 null。
         *
         * 注意：这里「不查数据库」—— 这正是 JWT 无状态的好处。
         * 唯一的例外是下面第 4 步查黑名单；如果业务能接受「Token 到期才失效」，
         * 那一步都可以直接删掉，就变成完全不查存储了。
         */
        Map<String, String> verify(String token) {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;   // 格式都不对，直接拒
            }

            String signingInput = parts[0] + "." + parts[1];

            // 第 1 步：验火漆（签名），防止内容被人偷偷改过
            if (!hmacSha256(signingInput).equals(parts[2])) {
                return null;
            }

            // 第 2 步：把载荷解出来
            Map<String, String> payload = parsePayload(parts[1]);

            // 第 3 步：看过期时间
            long expireAtSeconds = Long.parseLong(payload.get("exp"));
            if (System.currentTimeMillis() / 1000 > expireAtSeconds) {
                return null;
            }

            // 第 4 步：查黑名单（为了支持「立刻踢人下线」，JWT 付出的代价）
            if (store.exists(blacklistKey(payload.get("jti")))) {
                return null;
            }

            return payload;
        }

        /** 校验自管理随机 Token：只能查存储，查到什么就是什么。 */
        String verifyOpaqueToken(String opaqueToken) {
            return store.get("opaque:" + opaqueToken);
        }

        // ---------------- 撤回与续期 ----------------

        /** 把 Token 拉黑（用户登出 / 管理员强制下线时用）。 */
        void revoke(String token) {
            Map<String, String> payload = verify(token);
            if (payload == null) {
                return;   // Token 本身就不合法或已过期，不用拉黑了
            }
            long remainingSeconds = Long.parseLong(payload.get("exp")) - System.currentTimeMillis() / 1000;
            if (remainingSeconds > 0) {
                // 黑名单只需要存到「这张卡自然过期」的那一刻，过期后自动清理，省内存
                store.set(blacklistKey(payload.get("jti")), "revoked", remainingSeconds * 1000);
            }
        }

        /** 让自管理随机 Token 立刻失效：删掉存储里的记录即可。 */
        void disableOpaqueToken(String opaqueToken) {
            store.delete("opaque:" + opaqueToken);
        }

        /** 用 refresh_token 换一整套新 Token。 */
        TokenPair exchangeRefreshToken(String refreshToken) {
            String owner = store.get("refresh:" + refreshToken);
            if (owner == null) {
                return null;   // 不存在、已过期，或者已经用过了
            }
            // 轮换：旧的立刻作废，防止被人偷去反复使用
            store.delete("refresh:" + refreshToken);

            String[] ownerParts = owner.split("&");
            String userId = ownerParts[0];
            String clientId = ownerParts[1];

            String newAccessToken = issueAccessToken(userId, "read profile");
            String newRefreshToken = issueRefreshToken(userId, clientId);
            return new TokenPair(newAccessToken, newRefreshToken);
        }

        // ---------------- 内部小工具 ----------------

        private static String blacklistKey(String jti) {
            return "blacklist:jti:" + jti;
        }

        /** 用 HMAC-SHA256 算出签名（也就是上面说的「火漆印」）。 */
        private String hmacSha256(String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
                byte[] rawSignature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(rawSignature);
            } catch (Exception e) {
                throw new IllegalStateException("签名计算失败", e);
            }
        }

        /** Base64URL 编码（JWT 规定用 URL 安全的那套 Base64，避免出现 + / = 这些字符）。 */
        private static String base64UrlEncode(String text) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        /** 把载荷那一段解回 k=v 的键值对。 */
        private static Map<String, String> parsePayload(String encodedPayload) {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, String> payload = new HashMap<>();
            for (String pair : decoded.split("&")) {
                String[] keyValue = pair.split("=", 2);
                payload.put(keyValue[0], keyValue[1]);
            }
            return payload;
        }

        /** 生成一段随机十六进制字符串。 */
        private static String randomHex() {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /** 一次换 Token 会拿到两样东西，打包成一个 record 返回。 */
    record TokenPair(String accessToken, String refreshToken) {
    }

    // ==================================================================
    // 三、一些常量
    // ==================================================================

    // access_token 有效期：1 小时（真实项目常设 15 分钟 ~ 2 小时）
    static final long ACCESS_TOKEN_TTL_SECONDS = 60 * 60;

    // refresh_token 有效期：30 天（用户在这期间不用重新登录）
    static final long REFRESH_TOKEN_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;

    // 已注册的客户端：key 是 client_id，value 是 client_secret。
    // 真实项目存在数据库里，而且 secret 要加密存储。
    static final Map<String, String> REGISTERED_CLIENTS = Map.of(
            "web-app", "s3cret-key-do-not-leak"
    );

    // ==================================================================
    // 四、主流程：把五段演示依次跑一遍
    // ==================================================================
    public static void main(String[] args) {

        SimpleStore store = new SimpleStore();
        byte[] secretKey = "server-private-key".getBytes(StandardCharsets.UTF_8);
        TokenService tokenService = new TokenService(secretKey, store, ACCESS_TOKEN_TTL_SECONDS);

        String userId = "1001";
        String clientId = "web-app";

        // ---------------- 演示一：授权码模式 ----------------
        System.out.println("========== 演示一：授权码模式，用「临时取货码」换 Token ==========");

        // 第 1 步：用户在授权页登录并点了「同意授权」，授权服务发一张取货码给浏览器
        String authorizationCode = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        store.set("code:" + authorizationCode, userId + "&" + clientId, 5 * 60 * 1000L);
        System.out.println("① 授权服务发给浏览器一张临时取货码 authorization_code = " + authorizationCode);
        System.out.println("   浏览器只拿到这张「取货码」，拿不到真正的 Token。");

        // 第 2 步：客户端服务器拿取货码 + 自己的 client_secret，直接找授权服务换 Token
        String codeOwner = store.get("code:" + authorizationCode);
        store.delete("code:" + authorizationCode);   // 取货码只能用一次，用完立刻作废
        String[] ownerParts = codeOwner.split("&");
        String codeUserId = ownerParts[0];
        String codeClientId = ownerParts[1];

        String submittedSecret = REGISTERED_CLIENTS.get(clientId);   // 假装是客户端递过来的
        boolean secretMatches = codeClientId.equals(clientId)
                && REGISTERED_CLIENTS.get(clientId).equals(submittedSecret);
        System.out.println("② 客户端拿取货码 + client_secret 来换 Token，校验 client_secret："
                + (secretMatches ? "通过" : "失败"));
        System.out.println("   注意：这一步是「服务器对服务器」，浏览器全程看不到 access_token。");

        String accessToken = tokenService.issueAccessToken(codeUserId, "read profile");
        String refreshToken = tokenService.issueRefreshToken(codeUserId, clientId);
        System.out.println("   access_token  = " + shorten(accessToken));
        System.out.println("   refresh_token = " + shorten(refreshToken));
        System.out.println();

        // ---------------- 演示二：资源服务自己验签名 ----------------
        System.out.println("========== 演示二：资源服务自己验签名（JWT 无状态，不用查库） ==========");
        Map<String, String> payload = tokenService.verify(accessToken);
        System.out.println("资源服务收到请求，带上 access_token，验签结果：");
        System.out.println("   userId = " + payload.get("userId") + "，权限 = " + payload.get("scope"));
        System.out.println("   整个过程没有查数据库，这就是 JWT「无状态」的价值。");
        System.out.println("   （唯一的查询是「黑名单」那一步，如果允许 Token 到期才失效，那步也能省掉）");
        System.out.println();

        // ---------------- 演示三：JWT 的软肋 —— 登出后 Token 还在有效期内 ----------------
        System.out.println("========== 演示三：JWT 的软肋 —— 用户登出了，Token 却还没过期 ==========");
        System.out.println("登出前，拿旧 Token 访问资源："
                + (tokenService.verify(accessToken) != null ? "通过" : "拒绝"));
        tokenService.revoke(accessToken);
        System.out.println("用户点了「登出」，服务端把这张卡的卡号（jti）拉进了黑名单。");
        System.out.println("登出后，拿同一张旧 Token 再访问："
                + (tokenService.verify(accessToken) != null ? "通过" : "拒绝"));
        System.out.println("   → 这就是「JWT 不会自己失效」的补救办法：短期 Token + 黑名单。");
        System.out.println();

        // ---------------- 演示四：refresh_token 换新 Token ----------------
        System.out.println("========== 演示四：refresh_token 换新 Token，用户不用重新登录 ==========");
        TokenPair newTokenPair = tokenService.exchangeRefreshToken(refreshToken);
        System.out.println("用 refresh_token 换到了新的 access_token = " + shorten(newTokenPair.accessToken()));
        System.out.println("同时 refresh_token 也换成了新的        = " + shorten(newTokenPair.refreshToken()));
        System.out.println("旧的 refresh_token 已经作废（轮换），再拿去换："
                + (tokenService.exchangeRefreshToken(refreshToken) == null ? "被拒绝" : "居然还能用"));
        System.out.println("   → 这样即使 refresh_token 被偷，攻击者也最多只能用一次。");
        System.out.println();

        // ---------------- 演示五：另一种做法 —— 自管理随机 Token ----------------
        System.out.println("========== 演示五：自管理随机 Token（能随时踢人，但每次都要查存储） ==========");
        String opaqueToken = tokenService.issueOpaqueToken(userId);
        System.out.println("签发：一串随机字符 " + shorten(opaqueToken));
        System.out.println("      用户信息不在 Token 里，而是存在服务端存储中。");
        System.out.println("校验：每次请求都要查一次存储 → 查到 userId = "
                + tokenService.verifyOpaqueToken(opaqueToken));
        tokenService.disableOpaqueToken(opaqueToken);
        System.out.println("管理员点了「强制下线」，删掉存储里的记录。");
        System.out.println("再校验：查到 " + tokenService.verifyOpaqueToken(opaqueToken) + "（null 就是被拒了）");
        System.out.println("   → 好处是能立刻失效；代价是每次请求都得查存储，1k QPS 左右就可能成瓶颈。");
    }

    // ==================================================================
    // 五、打印用的小工具
    // ==================================================================

    /** Token 太长了，截短一点方便看。 */
    static String shorten(String token) {
        if (token.length() <= 32) {
            return token;
        }
        return token.substring(0, 32) + "...(共 " + token.length() + " 字符)";
    }
}
