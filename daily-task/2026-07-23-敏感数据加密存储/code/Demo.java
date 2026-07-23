// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================
// 这个程序在干嘛？
// ------------------------------------------------------------
// 演示「接口敏感数据（身份证、手机号）怎么加密传输和存储」这题里，
// 后端最核心的三件事（严格按原题给出的思路来）：
//
//   1) 需要还原的字段 → 用 AES 对称加密（存密文，用时能解回原文）
//   2) 只需比对的字段 → 用 SHA-256 + 随机盐 做哈希（不可逆，防彩虹表）
//   3) 返回给前端前  → 统一脱敏打码（如 手机号 138****1234）
//
// 最后 main 里把这三件事串成一条完整链路跑一遍：
//   加密存储 → 解密还原 → 加盐哈希比对 → 返回前脱敏
//
// 注意：HTTPS 传输属于服务器配置，不在这个单机 Demo 里体现。
// 全程只用 JDK 自带的 javax.crypto，不依赖任何外部库。
// ============================================================

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Demo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 敏感数据保护全链路演示 ==========\n");

        String realIdCard = "110101199003071234"; // 假的身份证号，仅用于演示
        String realPhone  = "13812341234";        // 假的手机号，仅用于演示

        // ---------- 环节一：需要还原的字段（身份证）→ AES 加密后存库 ----------
        System.out.println("【1. 可逆加密：身份证存密文，需要时能解回原文】");
        // AesCipher 内部会生成一把密钥。真实项目里这把钥匙必须交给 KMS 保管，
        // 绝对不能像这样写死在代码里 —— 这里为了能独立运行才临时生成。
        AesCipher aesCipher = new AesCipher();

        String encryptedIdCard = aesCipher.encrypt(realIdCard); // 存进数据库的应该是这一串密文
        System.out.println("  原文身份证 : " + realIdCard);
        System.out.println("  入库密文   : " + encryptedIdCard);

        String decryptedIdCard = aesCipher.decrypt(encryptedIdCard); // 查询时按需解密还原
        System.out.println("  解密还原   : " + decryptedIdCard);
        System.out.println("  还原是否正确: " + realIdCard.equals(decryptedIdCard));
        // 小验证：同一个身份证加密两次，密文应该不一样（因为每次用随机 IV），但都能解回同一原文
        System.out.println("  再加密一次 : " + aesCipher.encrypt(realIdCard) + "  ← 和上面的密文不同，这是好事\n");

        // ---------- 环节二：只需比对的字段 → SHA-256 + 盐 做哈希 ----------
        System.out.println("【2. 不可逆哈希：只判断是否相同，永远拿不回原文】");
        // 注册时：为这条数据生成一个随机盐，把 哈希值 和 盐 一起存库
        String salt = HashHelper.newSalt();
        String storedHash = HashHelper.hashWithSalt(realIdCard, salt);
        System.out.println("  存库盐值   : " + salt);
        System.out.println("  存库哈希   : " + storedHash);

        // 登录/查重时：拿用户输入的值 + 同一个盐 再算一次，比对哈希是否一致
        String userInput = "110101199003071234"; // 用户又输了一遍（正确）
        boolean match = HashHelper.verify(userInput, salt, storedHash);
        System.out.println("  输入正确值 → 比对结果: " + match);

        String wrongInput = "110101199003079999"; // 输错了
        boolean matchWrong = HashHelper.verify(wrongInput, salt, storedHash);
        System.out.println("  输入错误值 → 比对结果: " + matchWrong + "\n");

        // ---------- 环节三：返回给前端前 → 统一脱敏打码 ----------
        System.out.println("【3. 返回脱敏：接口吐给前端的一律打码】");
        System.out.println("  手机号脱敏 : " + realPhone + "  →  " + SensitiveMasker.maskPhone(realPhone));
        System.out.println("  身份证脱敏 : " + realIdCard + "  →  " + SensitiveMasker.maskIdCard(realIdCard) + "\n");

        // ---------- 串起来：模拟一次完整的「查询并返回」 ----------
        System.out.println("========== 模拟一次接口查询返回 ==========");
        // 后端从库里读出的是密文 encryptedIdCard，解密拿到真实值，
        // 但返回给前端时不能吐原文，要脱敏。
        String fromDb = encryptedIdCard;                       // 数据库里存的密文
        String realValue = aesCipher.decrypt(fromDb);          // 后端内部解密（自己用）
        String toFrontend = SensitiveMasker.maskIdCard(realValue); // 返回前端的脱敏值
        System.out.println("  数据库里存的   : " + fromDb);
        System.out.println("  后端内部解密后 : " + realValue + "  （仅后端可见）");
        System.out.println("  返回给前端的   : " + toFrontend + "  （用户看到的）");

        System.out.println("\n========== 演示结束，全链路 OK ==========");
    }

    // ============================================================
    // AesCipher：AES-GCM 可逆加密工具
    // 打个比方：AES 就像家门钥匙，同一把钥匙既能锁门（加密）也能开门（解密）。
    // 用 GCM 模式的好处：每次加密都带一个随机 IV（初始向量），
    // 所以同样的原文每次加出来的密文都不一样，还自带防篡改校验。
    // ============================================================
    static class AesCipher {
        private static final String ALGORITHM = "AES/GCM/NoPadding";
        private static final int IV_LENGTH = 12;      // GCM 推荐 12 字节 IV
        private static final int TAG_LENGTH_BIT = 128; // 校验标签长度

        private final SecretKeySpec secretKey;
        private final SecureRandom random = new SecureRandom();

        AesCipher() throws Exception {
            // 生成一把 256 位的 AES 密钥。
            // 【重要】真实项目里这把钥匙要从 KMS 取，绝不能写死在代码/配置里！
            byte[] keyBytes = new byte[32]; // 32 字节 = 256 位
            new SecureRandom().nextBytes(keyBytes);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        }

        // 加密：返回 Base64(IV + 密文)，把 IV 和密文拼在一起方便存储
        String encrypt(String plainText) throws Exception {
            // 每次都生成一个新的随机 IV，这是密文每次都不同的原因
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 把 IV 放在密文前面一起存：解密时要用同一个 IV
            byte[] ivAndCipher = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, ivAndCipher, 0, iv.length);
            System.arraycopy(cipherBytes, 0, ivAndCipher, iv.length, cipherBytes.length);

            return Base64.getEncoder().encodeToString(ivAndCipher);
        }

        // 解密：先把 IV 拆出来，再用它解出原文
        String decrypt(String base64IvAndCipher) throws Exception {
            byte[] ivAndCipher = Base64.getDecoder().decode(base64IvAndCipher);

            // 前 12 字节是 IV，剩下的才是真正的密文
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ivAndCipher, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[ivAndCipher.length - IV_LENGTH];
            System.arraycopy(ivAndCipher, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        }
    }

    // ============================================================
    // HashHelper：SHA-256 + 随机盐 的不可逆哈希工具
    // 哈希就像给内容拍一张「指纹照」：同样的内容永远同样的指纹，
    // 但你没法从指纹反推出原来的人是谁（单向、不可逆）。
    // 加「盐」是为了防彩虹表：在原文后面拼一段随机字符串再拍照，
    // 黑客提前算好的现成字典就全作废了。
    // ============================================================
    static class HashHelper {

        // 生成一个随机盐（每条数据一个），用 Base64 表示方便存储
        static String newSalt() {
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            return Base64.getEncoder().encodeToString(saltBytes);
        }

        // 把「原文 + 盐」一起做 SHA-256 哈希
        static String hashWithSalt(String plainText, String salt) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                // 关键：原文和盐拼在一起再哈希
                String salted = plainText + salt;
                byte[] hashBytes = digest.digest(salted.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(hashBytes);
            } catch (Exception e) {
                throw new RuntimeException("哈希计算失败", e);
            }
        }

        // 比对：用同一个盐再算一次，看结果是否和存的哈希一致
        static boolean verify(String input, String salt, String expectedHash) {
            String actualHash = hashWithSalt(input, salt);
            return actualHash.equals(expectedHash);
        }
    }

    // ============================================================
    // SensitiveMasker：脱敏打码工具
    // 对应原题里那段 Jackson 序列化器的核心逻辑：把原文中间几位换成 *，
    // 让用户/前端只看到打码后的样子。原则是「后端统一打码，别信前端」。
    // ============================================================
    static class SensitiveMasker {

        // 手机号：保留前 3 位和后 4 位，中间 4 位打码 → 138****1234
        static String maskPhone(String phone) {
            if (phone == null || phone.length() != 11) {
                return phone; // 长度不对就原样返回，交给上层处理
            }
            return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        }

        // 身份证：保留前 3 位和后 4 位，中间全部打码 → 110***********1234
        static String maskIdCard(String idCard) {
            if (idCard == null || idCard.length() < 8) {
                return idCard;
            }
            String prefix = idCard.substring(0, 3);
            String suffix = idCard.substring(idCard.length() - 4);
            // 中间用等量的 * 填充，长度和被遮住的位数一致
            int maskedCount = idCard.length() - 3 - 4;
            String stars = "*".repeat(maskedCount); // Java 11+ 的 String.repeat，Java 17 可用
            return prefix + stars + suffix;
        }
    }
}
