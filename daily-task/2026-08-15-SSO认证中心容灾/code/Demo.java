// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这个程序演示：SSO 认证中心宕机时，已登录用户为什么不受影响。
 * 核心思路（严格按原题）：
 *   1) 登录后发"自包含 Token"（JWT 风格，带签名），业务系统本地验签，不依赖认证中心；
 *   2) 本地用 Redis 风格的缓存存一份凭据映射，TTL 与 Token 一致，中心挂了也能扛一阵；
 *   3) （高可用属于运维侧，代码仅点出思路，不展开）。
 * 演示对比：中心宕机后，"每次远程验"会被踢下线，而"自包含 Token 本地验 / 本地缓存"仍放行。
 */
public class Demo {

    // 只有认证中心和各业务系统知道的秘钥，用来给 Token 签名、验签
    private static final String SECRET = "sso-demo-secret-key-2026";
    // Token 默认有效期：10 秒（演示用，真实场景通常是几十分钟）
    private static final long TTL_MILLIS = 10_000L;

    /** 自包含 Token 工具：签发 / 验签 / 过期判断 / 取用户ID，全部本地完成，不碰认证中心 */
    static class JwtUtil {
        // base64url 编码（去掉末尾的 = 号，符合 JWT 规范）
        private static String b64url(byte[] data) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        }
        private static byte[] b64urlDecode(String s) {
            return Base64.getUrlDecoder().decode(s);
        }
        // 用秘钥对 "头.体" 算 HMAC-SHA256 指纹，作为签名（就像盖防伪章）
        private static byte[] hmac(String data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }

        /** 签发 Token：payload 里只放 sub(用户ID) 和 exp(过期毫秒时间戳) */
        static String sign(String userId, long expMillis) throws Exception {
            String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String payload = "sub=" + userId + "&exp=" + expMillis;
            String h = b64url(header.getBytes(StandardCharsets.UTF_8));
            String p = b64url(payload.getBytes(StandardCharsets.UTF_8));
            String sig = b64url(hmac(h + "." + p));
            return h + "." + p + "." + sig;
        }

        /** 验签：重新算一遍签名，和 Token 里的比对（常量时间比较，防时序攻击） */
        static boolean validate(String token) throws Exception {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;
            String expected = b64url(hmac(parts[0] + "." + parts[1]));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8));
        }

        /** 是否过期：exp 在现在之前就算过期 */
        static boolean isExpired(String token) throws Exception {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return true;
            String payload = new String(b64urlDecode(parts[1]), StandardCharsets.UTF_8);
            long exp = Long.parseLong(payload.split("&exp=")[1]);
            return System.currentTimeMillis() > exp;
        }

        /** 综合校验：签名对 + 没过期，才放行（对应原题 validate && !isExpired） */
        static boolean verify(String token) throws Exception {
            return validate(token) && !isExpired(token);
        }

        /** 从 Token 里取出用户ID */
        static String parseUserId(String token) throws Exception {
            String[] parts = token.split("\\.");
            String payload = new String(b64urlDecode(parts[1]), StandardCharsets.UTF_8);
            String sub = payload.split("&exp=")[0]; // "sub=u1001"
            return sub.substring("sub=".length());
        }
    }

    /** 模拟 Redis：存 token→用户信息，带 TTL。中心宕机时业务系统翻它兜底 */
    static class LocalSessionCache {
        // 用普通的 Map 模拟 Redis；key=token，value=带过期时间的会话
        private final Map<String, SessionEntry> store = new ConcurrentHashMap<>();
        private record SessionEntry(String userId, long expireAt) {
            boolean alive() { return System.currentTimeMillis() <= expireAt; }
        }
        void put(String token, String userId, long ttlMillis) {
            store.put(token, new SessionEntry(userId, System.currentTimeMillis() + ttlMillis));
        }
        /** 取出未过期的会话；过期或不存在返回 null（像 Redis 里 key 已失效） */
        String getUserId(String token) {
            SessionEntry e = store.get(token);
            if (e == null || !e.alive()) return null;
            return e.userId;
        }
    }

    /** 认证中心：平时在线，宕机后"远程校验"直接抛异常（模拟网络不通） */
    static class AuthCenter {
        boolean alive = true;            // 中心是否存活
        int remoteCallCount = 0;         // 统计远程校验被调用次数，用于证明本地路径不依赖它

        /** 登录：中心在线时签发 Token（同时会把凭据写进本地缓存，见 main） */
        String login(String userId) throws Exception {
            if (!alive) throw new RuntimeException("认证中心宕机，无法登录");
            return JwtUtil.sign(userId, System.currentTimeMillis() + TTL_MILLIS);
        }
        /** 反例：每次请求都远程验一次——中心一挂，全员重登 */
        boolean validateRemote(String token) throws Exception {
            remoteCallCount++;
            if (!alive) throw new RuntimeException("认证中心宕机，远程校验失败");
            return JwtUtil.verify(token);
        }
    }

    public static void main(String[] args) throws Exception {
        AuthCenter center = new AuthCenter();
        LocalSessionCache cache = new LocalSessionCache();

        System.out.println("=== 场景一：认证中心正常 ===");
        center.alive = true;
        String token = center.login("u1001");
        cache.put(token, "u1001", TTL_MILLIS);   // 登录同时把凭据写进本地缓存
        System.out.println("远程校验:           " + center.validateRemote(token));      // true
        System.out.println("自包含Token本地验:  " + JwtUtil.verify(token));             // true
        System.out.println("本地缓存校验:       " + (cache.getUserId(token) != null)); // true

        System.out.println("\n=== 场景二：认证中心宕机（已登录用户是否受影响？）===");
        center.alive = false;
        // 反例：依赖远程校验 -> 被踢下线
        try {
            center.validateRemote(token);
            System.out.println("远程校验:           仍放行（意外）");
        } catch (RuntimeException e) {
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
        boolean cacheOk = cache.getUserId(token) != null;
        System.out.println("本地缓存校验:       " + cacheOk + "（用户无感继续访问）");

        System.out.println("\n=== 场景三：Token 过期了，该重登还是得重登 ===");
        String expired = JwtUtil.sign("u1002", System.currentTimeMillis() - 1000); // 签发时间在过去
        System.out.println("已过期Token本地校验是否通过: " + JwtUtil.verify(expired)
                + "（应为 false：过期必须重新登录，本地校验不保永生）");

        System.out.println("\n=== 小结 ===");
        System.out.println("在线验证只发生在登录/刷新那一刻；日常请求靠自包含Token+本地校验兜底，");
        System.out.println("所以认证中心挂了，已登录用户完全无感。");
    }
}
