// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================
// 这个程序在干嘛
// ============================================================
// 题目：数据库里存 IP 地址，该用什么类型？
// 结论：别当字符串存。
//       IPv4 -> INT UNSIGNED（4 字节整数），用 MySQL 的 INET_ATON / INET_NTOA 转换；
//       IPv6 -> VARBINARY(16)（16 字节二进制），用 INET6_ATON / INET6_NTOA 转换。
//
// 这个 Demo 演示的就是「Java 这一侧要配合做的翻译工作」：
//   存库之前：把 IP 字符串翻译成整数 / 字节数组；
//   取库之后：把整数 / 字节数组翻译回人能看懂的 IP 字符串。
//
// 程序分四段：
//   第 1 段：IPv4 字符串 <-> 整数 互转（手写实现，逻辑和 MySQL 的 INET_ATON 一模一样）
//   第 2 段：为什么 Java 里必须用 long 接 —— 用 int 会溢出成负数
//   第 3 段：IPv6 转成 16 字节二进制，再转回字符串
//   第 4 段：存整数的好处 —— 查「某个网段」只要比大小，不用拼字符串
// ============================================================

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class Demo {

    public static void main(String[] args) throws UnknownHostException {
        demoIpv4Convert();
        demoWhyUseLong();
        demoIpv6Binary();
        demoQueryByRange();
    }

    // ==========================================================
    // 第 1 段：IPv4 字符串 <-> 整数
    // ==========================================================
    private static void demoIpv4Convert() {
        System.out.println("===== 第 1 段：IPv4 字符串和整数互转（等价于 INET_ATON / INET_NTOA）=====");

        String ipText = "192.168.1.1";

        // 存库：字符串 -> 整数，这一步相当于 SQL 里的 INET_ATON('192.168.1.1')
        long ipNumber = ipv4ToNumber(ipText);
        System.out.println("存进去：字符串 " + ipText + "  ->  整数 " + ipNumber);

        // 取库：整数 -> 字符串，这一步相当于 SQL 里的 INET_NTOA(ip_num)
        String backToText = numberToIpv4(ipNumber);
        System.out.println("读出来：整数 " + ipNumber + "  ->  字符串 " + backToText);
        System.out.println();
    }

    /**
     * IP 字符串 -> 整数。对应 MySQL 的 INET_ATON。
     *
     * 思路：把 4 段数字当成「256 进制」拼起来。
     * 生活里也有类似算法：把「1 小时 2 分 3 秒」统一换算成秒数，
     * 一个数字就能比大小、能排序，不用逐段去比。
     */
    private static long ipv4ToNumber(String ipText) {
        String[] fourParts = ipText.split("\\.");
        long result = 0;
        for (String part : fourParts) {
            // 每往左挪一段，就相当于进位一次，所以要 × 256
            result = result * 256 + Integer.parseInt(part);
        }
        return result;
    }

    /**
     * 整数 -> IP 字符串。对应 MySQL 的 INET_NTOA。
     *
     * 思路：从高位往低位，每次抠出一个字节。
     * 抠字节的办法是「右移 + & 0xFF」：右移把目标字节挪到最低位，
     * & 0xFF 表示「只保留最低的 8 位」，其余清零。
     */
    private static String numberToIpv4(long ipNumber) {
        StringBuilder builder = new StringBuilder();
        for (int shift = 24; shift >= 0; shift -= 8) {
            long oneByte = (ipNumber >> shift) & 0xFF;
            builder.append(oneByte);
            if (shift > 0) {
                builder.append('.');
            }
        }
        return builder.toString();
    }

    // ==========================================================
    // 第 2 段：为什么必须用 long 接
    // ==========================================================
    private static void demoWhyUseLong() {
        System.out.println("===== 第 2 段：为什么 Java 里必须用 long 接这个字段 =====");

        String bigIpText = "200.1.1.1";

        // 数据库里真实存的值（正确值，用 long 装得下）
        long correctNumber = ipv4ToNumber(bigIpText);

        // 故意强转成 int，模拟「Java 里用 int 去接 MySQL 的 INT UNSIGNED」会发生什么
        int wrongNumber = (int) correctNumber;

        System.out.println("IP " + bigIpText + " 在数据库里的值：" + correctNumber);
        System.out.println("  INT UNSIGNED 能存到 4294967295，数据库没问题");
        System.out.println("  但 Java 的 int 最大只有 2147483647，用 int 接就变成：" + wrongNumber + "（负数，数据错了）");
        System.out.println("所以：JDBC 用 rs.getLong(...)，接收变量也声明成 long。");
        System.out.println();
    }

    // ==========================================================
    // 第 3 段：IPv6 存成 16 字节二进制
    // ==========================================================
    private static void demoIpv6Binary() throws UnknownHostException {
        System.out.println("===== 第 3 段：IPv6 存成 16 字节二进制（等价于 INET6_ATON / INET6_NTOA）=====");

        String ipv6Text = "2001:db8::1";

        // 存库：字符串 -> 16 字节二进制（Java 自带的 InetAddress 就能解析，不用连网络）
        byte[] ipv6Bytes = InetAddress.getByName(ipv6Text).getAddress();
        System.out.println("存进去：字符串 " + ipv6Text
                + "  ->  " + ipv6Bytes.length + " 字节二进制 " + bytesToHex(ipv6Bytes));

        // 取库：16 字节二进制 -> 字符串
        String backToText = InetAddress.getByAddress(ipv6Bytes).getHostAddress();
        System.out.println("读出来：16 字节二进制  ->  字符串 " + backToText);
        System.out.println("VARBINARY(16) 正好装下 128 位，不多不少。");
        System.out.println();
    }

    /** 把字节数组打印成十六进制，方便肉眼看「二进制到底存了什么」。 */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte oneByte : bytes) {
            builder.append(String.format("%02X", oneByte));
        }
        return builder.toString();
    }

    // ==========================================================
    // 第 4 段：存整数后，查网段只要比大小
    // ==========================================================
    private static void demoQueryByRange() {
        System.out.println("===== 第 4 段：查网段只要比大小（这就是存整数的最大好处）=====");

        // 假装这是数据库表里的一列 ip_num，存的都是已经转好的整数
        List<Long> storedIpNumbers = new ArrayList<>();
        storedIpNumbers.add(ipv4ToNumber("192.168.1.1"));
        storedIpNumbers.add(ipv4ToNumber("192.168.1.200"));
        storedIpNumbers.add(ipv4ToNumber("192.168.2.5"));
        storedIpNumbers.add(ipv4ToNumber("10.0.0.8"));

        // 需求：找出 192.168.1.0/24 这个网段的所有 IP
        // 换成整数语言就是：值落在 192.168.1.0 ~ 192.168.1.255 之间
        long rangeStart = ipv4ToNumber("192.168.1.0");
        long rangeEnd = ipv4ToNumber("192.168.1.255");

        System.out.println("SQL 里就是：SELECT ... WHERE ip_num BETWEEN " + rangeStart + " AND " + rangeEnd);
        System.out.println("命中的 IP：");
        for (long ipNumber : storedIpNumbers) {
            if (ipNumber >= rangeStart && ipNumber <= rangeEnd) {
                System.out.println("  " + numberToIpv4(ipNumber));
            }
        }
        System.out.println("如果 IP 存的是字符串，只能拼 LIKE '192.168.1.%'，慢、还容易误伤（比如 192.168.10.x）。");
    }
}
