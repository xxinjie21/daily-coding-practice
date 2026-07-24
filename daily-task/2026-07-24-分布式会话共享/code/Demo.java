// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 【这个程序在干嘛】
// 主题：多台服务器如何共享用户登录信息。
// 我们用一段可以直接跑起来的小程序，把原题给出的三种思路各演示一遍：
//   方案一：Redis 分布式会话  —— 用一个大家共用的 Map 模拟 Redis（公共储物柜），
//           两台服务器都从同一个 Map 取登录信息，所以谁都认得已登录的用户。
//   方案二：JWT 无状态令牌     —— 用真实的 HmacSHA256 演示"签发令牌 + 验签"，
//           服务端不存任何东西，靠验签就能识别用户；还演示"篡改后验签失败"。
//   方案三：IP Hash 粘性会话   —— 演示负载均衡按 IP 固定分配机器，
//           以及这台机器一挂、会话就丢的问题。
//
// 为了聚焦核心机制，这里不连真的 Redis、不起真的 HTTP 服务，全部用内存对象模拟，
// 但每种方案"数据怎么流、为什么能共享/不能共享"都如实还原。

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Demo {

    public static void main(String[] args) throws Exception {
        System.out.println("======== 方案一：Redis 分布式会话（推荐） ========");
        demoRedisSession();

        System.out.println();
        System.out.println("======== 方案二：JWT 无状态令牌 ========");
        demoJwt();

        System.out.println();
        System.out.println("======== 方案三：IP Hash 粘性会话（临时方案） ========");
        demoIpHash();
    }

    // ============================================================
    // 方案一：Redis 分布式会话
    // 思路：把登录档案从"每台机自己的内存"搬到"所有机共用的 Redis"。
    // 这里用一个共享的 HashMap 冒充 Redis：两台服务器都持有它的引用，
    // 所以一台写入的登录信息，另一台立刻就能读到。
    // ============================================================
    static void demoRedisSession() {
        // 这个 Map 就是"公共储物柜"，模拟 Redis。key=sessionId，value=用户档案。
        Map<String, UserProfile> fakeRedis = new HashMap<>();

        // 造两台无状态的服务器，它们共用同一个 fakeRedis（就像共用同一个 Redis）。
        ServerNode serverA = new ServerNode("服务器A", fakeRedis);
        ServerNode serverB = new ServerNode("服务器B", fakeRedis);

        // 第一步：用户在【服务器A】登录，A 把档案写进公共储物柜，返回一把"钥匙"(sessionId)。
        String sessionId = serverA.login("张三", "admin");
        System.out.println("张三在【服务器A】登录成功，拿到 sessionId=" + shorten(sessionId));

        // 第二步：下一次请求被负载均衡分给了【服务器B】。B 拿着同一把钥匙去公共储物柜查。
        UserProfile whoAmIonB = serverB.whoAmI(sessionId);
        if (whoAmIonB != null) {
            System.out.println("同一把钥匙拿到【服务器B】查询 -> 认得！用户是：" + whoAmIonB.userName
                    + "（权限：" + whoAmIonB.role + "）");
        } else {
            System.out.println("同一把钥匙在【服务器B】查不到（不该发生）");
        }

        // 第三步：演示"踢人下线"有多方便——只要把公共储物柜里对应的 key 删掉即可。
        serverA.logout(sessionId);
        System.out.println("张三被踢下线（删除了 Redis 里的 key）");
        UserProfile afterLogout = serverB.whoAmI(sessionId);
        System.out.println("再用旧钥匙在【服务器B】查询 -> "
                + (afterLogout == null ? "已失效，提示未登录 ✅" : "居然还在（不该发生）"));
    }

    // ============================================================
    // 方案二：JWT 无状态令牌
    // 思路：服务端不存档案，把用户信息签进一张"防伪令牌"发给客户端自己带。
    // 服务端只需用密钥验签，就能确认令牌真伪、识别用户。
    // 这里用真实的 HmacSHA256 演示签发、验签、以及"被篡改后验签失败"。
    // ============================================================
    static void demoJwt() throws Exception {
        // 服务端密钥：只有服务器知道。多台机器共享同一把密钥即可，无需任何存储。
        String secretKey = "my-very-secret-key-only-server-knows";

        // 第一步：用户登录成功，服务端签发 JWT（把用户信息 + 防伪签名打包）。
        String payload = "{\"userId\":1001,\"userName\":\"李四\",\"role\":\"user\"}";
        String token = createJwt(payload, secretKey);
        System.out.println("李四登录成功，服务端签发 JWT：" + shorten(token));

        // 第二步：任意一台服务器收到请求，只需验签就能识别用户，全程不查存储。
        boolean ok = verifyJwt(token, secretKey);
        System.out.println("任意服务器收到该令牌，验签结果 -> " + (ok ? "有效，识别为李四 ✅" : "无效"));

        // 第三步：坏人偷偷篡改令牌内容（想把自己改成 admin），演示验签会立刻识破。
        String[] parts = token.split("\\.");
        String tamperedPayload = base64UrlEncode(
                "{\"userId\":1001,\"userName\":\"李四\",\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        // 只改内容、不改签名（因为坏人没有密钥算不出新签名）
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];
        boolean tamperedOk = verifyJwt(tamperedToken, secretKey);
        System.out.println("坏人把 role 偷改成 admin 后再验签 -> "
                + (tamperedOk ? "居然通过（不该发生）" : "签名对不上，拒绝 ✅"));
    }

    // ============================================================
    // 方案三：IP Hash 粘性会话（临时方案）
    // 思路：负载均衡按客户端 IP 做哈希，让同一 IP 每次固定命中同一台机器，
    // 于是会话放本机内存就够。缺点：那台机器一挂，它上面的会话全丢。
    // ============================================================
    static void demoIpHash() {
        // 假设后面有 3 台服务器
        String[] servers = {"服务器0", "服务器1", "服务器2"};

        // 负载均衡用 IP 的哈希值对机器数取模，决定这个 IP 固定走哪台。
        String clientIp = "203.0.113.55";
        int index = pickServerByIpHash(clientIp, servers.length);
        System.out.println("客户端 IP=" + clientIp + " 被固定分配到 -> " + servers[index]);

        // 同一个 IP 再来几次，结果始终一样，所以会话能一直命中同一台机器。
        int again = pickServerByIpHash(clientIp, servers.length);
        System.out.println("同一 IP 再次请求 -> " + servers[again] + "（始终不变，所以会话能命中）");

        // 演示缺点：如果这台机器挂了，机器数变成 2，同一 IP 会被重新分到别处，
        // 原本存在旧机器内存里的会话就丢了。
        int afterCrash = pickServerByIpHash(clientIp, servers.length - 1);
        System.out.println("若该机器宕机（可用机器减为2台）-> 同一 IP 改分到 服务器" + afterCrash
                + "，旧机器内存里的会话丢失 ⚠️");
    }

    // ---------------- 下面是各方案用到的小工具 ----------------

    // 一台"无状态"服务器：自己不存登录信息，全部读写共用的 fakeRedis。
    static class ServerNode {
        final String name;
        final Map<String, UserProfile> sharedStore; // 指向公共储物柜(Redis)

        ServerNode(String name, Map<String, UserProfile> sharedStore) {
            this.name = name;
            this.sharedStore = sharedStore;
        }

        // 登录：生成钥匙(sessionId) -> 把档案写进公共储物柜 -> 返回钥匙给浏览器
        String login(String userName, String role) {
            String sessionId = UUID.randomUUID().toString();
            sharedStore.put(sessionId, new UserProfile(userName, role));
            return sessionId;
        }

        // 校验：拿钥匙去公共储物柜取档案，取到就是已登录
        UserProfile whoAmI(String sessionId) {
            return sharedStore.get(sessionId);
        }

        // 踢人下线：把公共储物柜里的这把钥匙删掉
        void logout(String sessionId) {
            sharedStore.remove(sessionId);
        }
    }

    // 用户档案（Session 里装的东西）
    static class UserProfile {
        final String userName;
        final String role;

        UserProfile(String userName, String role) {
            this.userName = userName;
            this.role = role;
        }
    }

    // 生成一张 JWT：Header.Payload.Signature 三段用点号连接
    static String createJwt(String payloadJson, String secretKey) throws Exception {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String headerPart = base64UrlEncode(header.getBytes(StandardCharsets.UTF_8));
        String payloadPart = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        // 签名 = 用密钥对 "头.载荷" 算 HmacSHA256（这就是防伪章）
        String signature = sign(headerPart + "." + payloadPart, secretKey);
        return headerPart + "." + payloadPart + "." + signature;
    }

    // 验签：拿到令牌后，用同一把密钥重新算一遍签名，和令牌里带的签名比对是否一致
    static boolean verifyJwt(String token, String secretKey) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        String expectedSignature = sign(parts[0] + "." + parts[1], secretKey);
        // 一致 = 没被篡改、确实是本服务端签发的
        return expectedSignature.equals(parts[2]);
    }

    // 用 HmacSHA256 算签名（真实加密算法，不是伪代码）
    static String sign(String data, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(result);
    }

    // 负载均衡的 IP Hash：用 IP 的哈希值对机器数取模，得到固定的机器编号
    static int pickServerByIpHash(String clientIp, int serverCount) {
        // Math.abs 防止哈希为负；取模让结果落在 [0, serverCount) 范围
        return Math.abs(clientIp.hashCode()) % serverCount;
    }

    // Base64 URL 编码（JWT 用的是 URL 安全、无填充的变体）
    static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 把很长的字符串截短，方便打印时看得清楚
    static String shorten(String s) {
        return s.length() <= 16 ? s : s.substring(0, 16) + "...";
    }
}
