// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「身份证、手机号这类敏感数据，怎么加密传输和存储」。后端最核心的三件事各演示一遍：
//     ① 需要还原的字段 → AES-GCM 对称加密：存密文，用时能解回原文
//     ② 只需比对的字段 → SHA-256 + 随机盐 哈希：不可逆，防彩虹表
//     ③ 返回给前端前   → 统一脱敏打码（手机号 138****1234）
//   main 把三件事串成一条链路：加密存储 → 解密还原 → 加盐哈希比对 → 返回前脱敏。
//
// 前置条件：只用 JDK 自带的 javax.crypto，不连任何中间件。
// 注意：HTTPS 传输属于服务器配置，不在这个单机 Demo 的范围内。

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Demo {

    public static void main(String[] args) throws Exception {
        String idCard = "110101199003071234"; // 假的身份证号，仅用于演示
        String phone = "13812341234";         // 假的手机号，仅用于演示

        System.out.println("【1. 可逆加密：身份证存密文，需要时能解回原文】");
        AesCipher aes = new AesCipher();
        String cipherText = aes.encrypt(idCard);          // 入库的就是这一串密文
        System.out.println("  原文身份证 : " + idCard);
        System.out.println("  入库密文   : " + cipherText);
        System.out.println("  解密还原   : " + aes.decrypt(cipherText));
        // 同一原文加密两次密文不同，因为每次都用新的随机 IV：攻击者没法靠比对密文猜出原文
        System.out.println("  再加密一次 : " + aes.encrypt(idCard) + "  ← 与上面的密文不同，这是好事");

        System.out.println("\n【2. 不可逆哈希：只判断是否相同，永远拿不回原文】");
        // 注册时：给这条数据生成一个随机盐，把哈希值和盐一起存库
        String salt = HashHelper.newSalt();
        String storedHash = HashHelper.hashWithSalt(idCard, salt);
        System.out.println("  存库盐值   : " + salt);
        System.out.println("  存库哈希   : " + storedHash);
        // 查重/比对时：拿用户输入 + 同一个盐再算一次，比哈希是否一致
        System.out.println("  输入正确值 → 比对结果: " + HashHelper.verify(idCard, salt, storedHash));
        System.out.println("  输入错误值 → 比对结果: "
                + HashHelper.verify("110101199003079999", salt, storedHash));

        System.out.println("\n【3. 返回脱敏：接口吐给前端的一律打码】");
        System.out.println("  手机号脱敏 : " + phone + " → " + SensitiveMasker.maskPhone(phone));
        System.out.println("  身份证脱敏 : " + idCard + " → " + SensitiveMasker.maskIdCard(idCard));

        System.out.println("\n========== 模拟一次接口查询返回 ==========");
        // 库里读出来是密文，后端内部按需解密自己用，但绝不能把原文吐给前端
        String realValue = aes.decrypt(cipherText);
        System.out.println("  数据库里存的   : " + cipherText);
        System.out.println("  后端内部解密后 : " + realValue + "  （仅后端可见）");
        System.out.println("  返回给前端的   : " + SensitiveMasker.maskIdCard(realValue) + "  （用户看到的）");
    }

    // ==================================================================
    // AES-GCM 可逆加密：AES 像家门钥匙，同一把钥匙既能锁门也能开门。
    // 用 GCM 模式的好处是每次加密带一个随机 IV，同样的原文加出来的密文也不一样，
    // 而且自带防篡改校验（密文被改过，解密会直接报错）。
    // ==================================================================
    static class AesCipher {
        private static final String ALGORITHM = "AES/GCM/NoPadding";
        private static final int IV_LENGTH = 12;        // GCM 推荐 12 字节 IV
        private static final int TAG_LENGTH_BIT = 128;  // 防篡改校验标签长度

        private final SecretKeySpec secretKey;
        private final SecureRandom random = new SecureRandom();

        AesCipher() {
            // 【重要】真实项目里这把钥匙必须从 KMS 取，绝不能写死在代码或配置里。
            // 这里为了能独立运行，临时随机生成一把 256 位密钥。
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        }

        /** 加密：返回 Base64(IV + 密文)，IV 拼在密文前面，解密时要用同一个 IV。 */
        String encrypt(String plainText) throws Exception {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCipher = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, ivAndCipher, 0, iv.length);
            System.arraycopy(cipherBytes, 0, ivAndCipher, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(ivAndCipher);
        }

        /** 解密：先把前 12 字节 IV 拆出来，再用它解出原文。 */
        String decrypt(String base64IvAndCipher) throws Exception {
            byte[] ivAndCipher = Base64.getDecoder().decode(base64IvAndCipher);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ivAndCipher, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[ivAndCipher.length - IV_LENGTH];
            System.arraycopy(ivAndCipher, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        }
    }

    // ==================================================================
    // SHA-256 + 随机盐：哈希像给内容拍一张「指纹照」，同样的内容永远同样的指纹，
    // 但没法从指纹反推出原来的人是谁（单向、不可逆）。
    // 加盐是为了防彩虹表：在原文后面拼一段随机字符串再拍照，
    // 黑客提前算好的现成字典就全作废了。
    // ==================================================================
    static class HashHelper {

        /** 每条数据一个随机盐，用 Base64 表示方便存库。 */
        static String newSalt() {
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            return Base64.getEncoder().encodeToString(saltBytes);
        }

        /** 关键：原文和盐拼在一起再哈希。 */
        static String hashWithSalt(String plainText, String salt) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest((plainText + salt).getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(hashBytes);
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
        }

        /** 比对：用同一个盐再算一次，看结果是否和存库的哈希一致。 */
        static boolean verify(String input, String salt, String expectedHash) {
            return hashWithSalt(input, salt).equals(expectedHash);
        }
    }

    // ==================================================================
    // 脱敏打码：对应原题里 Jackson 序列化器的核心逻辑，把原文中间几位换成 *。
    // 原则是「后端统一打码，别信前端」—— 前端拿不到原文，也就不可能漏出去。
    // ==================================================================
    static class SensitiveMasker {

        /** 手机号：保留前 3 位和后 4 位 → 138****1234 */
        static String maskPhone(String phone) {
            if (phone == null || phone.length() != 11) {
                return phone;
            }
            return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        }

        /** 身份证：保留前 3 位和后 4 位，中间用等量 * 填满 → 110***********1234 */
        static String maskIdCard(String idCard) {
            if (idCard == null || idCard.length() < 8) {
                return idCard;
            }
            int maskedCount = idCard.length() - 3 - 4;
            return idCard.substring(0, 3) + "*".repeat(maskedCount) + idCard.substring(idCard.length() - 4);
        }
    }
}
