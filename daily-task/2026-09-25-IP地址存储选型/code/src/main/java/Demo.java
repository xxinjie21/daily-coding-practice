// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题：数据库里存 IP 地址，该用什么类型？
//
// 结论：别用 varchar 把 IP 当文字存。
//   IPv4 -> INT UNSIGNED（4 字节整数），靠 MySQL 的 INET_ATON / INET_NTOA 转换；
//   IPv6 -> VARBINARY(16)（16 字节二进制），靠 INET6_ATON / INET6_NTOA 转换。
//
// 这个 Demo 连真 MySQL，把 SQL 真的跑一遍（不是写在注释里的假 SQL）：
//   第 0 段：Java 侧最容易踩的坑——为什么必须用 long 接（纯计算，不用连库）
//   第 1 段：建表 + INET_ATON 写库 + INET_NTOA 读回
//   第 2 段：字符串排序会乱——'192.168.1.10' 排在 '192.168.1.9' 前面
//   第 3 段：查网段只要比大小，EXPLAIN / SHOW INDEX 确认走索引
//   第 4 段：IPv6 用 VARBINARY(16) + INET6_ATON / INET6_NTOA
//
// 前置条件：本机起一个 MySQL（见同目录 docker-compose.yml，localhost:3306，root/root，库名 demo）。
// 运行：mvn -q compile exec:java
// ============================================================================

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

public class Demo {

    static final String JDBC_URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws SQLException {
        demoJavaSideOverflow(); // 先讲纯 Java 侧的坑，不需要连库

        try (Connection conn = DriverManager.getConnection(JDBC_URL, "root", "root")) {
            demoIpv4Table(conn);
            demoSortTrap(conn);
            demoRangeQuery(conn);
            demoIpv6Table(conn);
        }
    }

    // ========================================================================
    // 第 0 段：Java 侧为什么必须用 long 接
    //   MySQL 的 INT UNSIGNED 上限 4294967295，Java 的 int 上限 2147483647 —— 刚好差一倍。
    // ========================================================================
    private static void demoJavaSideOverflow() {
        System.out.println("---------- 第 0 段：Java 侧必须用 long 接（最容易踩的坑） ----------");

        long correctNumber = ipv4ToNumber("200.1.1.1"); // 数据库里真实存的值
        int wrongNumber = (int) correctNumber;           // 模拟「用 int 去接 INT UNSIGNED」

        System.out.println("IP 200.1.1.1 在库里的值：" + correctNumber);
        System.out.println("  INT UNSIGNED 能存到 4294967295，数据库这边没问题；");
        System.out.println("  但 Java 的 int 最大只有 2147483647，用 int 接就变成 " + wrongNumber + "（负数，数据错了）");
        System.out.println("  → JDBC 用 rs.getLong(...)，接收变量也声明成 long。");
    }

    /** IP 字符串 -> 整数。逻辑和 MySQL 的 INET_ATON 一模一样：4 段数字按 256 进制拼起来。 */
    private static long ipv4ToNumber(String ipText) {
        long result = 0;
        for (String part : ipText.split("\\.")) {
            result = result * 256 + Integer.parseInt(part); // 每往左挪一段就进位一次，所以 ×256
        }
        return result;
    }

    // ========================================================================
    // 第 1 段：建表 + 写库 + 读回
    //   生活比喻：门牌号「幸福路 88 号 3 单元 501」抄成一整行字太占地方，
    //   其实它背后就是几个数字，记成编号又快又省。
    // ========================================================================
    private static void demoIpv4Table(Connection conn) throws SQLException {
        System.out.println("\n---------- 第 1 段：IPv4 存成 INT UNSIGNED（4 字节） ----------");

        execute(conn, "DROP TABLE IF EXISTS user_log");
        execute(conn, """
                CREATE TABLE user_log (
                  id      BIGINT UNSIGNED PRIMARY KEY,
                  ip_text VARCHAR(15) NOT NULL,   -- 人看的写法，留着做对比
                  ip_num  INT UNSIGNED NOT NULL,  -- 真正建索引、真正用来比大小的列
                  KEY idx_ip_num (ip_num)
                ) ENGINE=InnoDB
                """);
        execute(conn, """
                INSERT INTO user_log (id, ip_text, ip_num) VALUES
                  (1, '192.168.1.1',   INET_ATON('192.168.1.1')),
                  (2, '192.168.1.9',   INET_ATON('192.168.1.9')),
                  (3, '192.168.1.10',  INET_ATON('192.168.1.10')),
                  (4, '192.168.1.200', INET_ATON('192.168.1.200')),
                  (5, '192.168.2.5',   INET_ATON('192.168.2.5')),
                  (6, '10.0.0.8',      INET_ATON('10.0.0.8'))
                """);
        System.out.println("建表 + 写入完成，读回来看看（INET_NTOA 把整数翻译回字符串）：");
        printQuery(conn, "SELECT id, ip_text, ip_num, INET_NTOA(ip_num) AS back_to_text FROM user_log ORDER BY id");
        System.out.println("→ 192.168.1.1 换算成 3232235777，只占 4 字节；varchar(15) 要占 15 字节。");
    }

    // ========================================================================
    // 第 2 段：字符串排序会乱 —— 这就是不能用 varchar 的第一个理由
    // ========================================================================
    private static void demoSortTrap(Connection conn) throws SQLException {
        System.out.println("\n---------- 第 2 段：字符串排序会乱（存整数的第一个理由） ----------");

        System.out.println("按 ip_text 字符串的字典序排（注意 9 和 10 的顺序）：");
        printQuery(conn, "SELECT ip_text FROM user_log WHERE ip_text LIKE '192.168.1.%' ORDER BY ip_text");
        System.out.println("按 ip_num 整数排（这才是对的）：");
        printQuery(conn, "SELECT INET_NTOA(ip_num) AS ip FROM user_log WHERE ip_text LIKE '192.168.1.%' ORDER BY ip_num");
        System.out.println("→ 字符串按字典序比，'192.168.1.10' 排到了 '192.168.1.9' 前面，肉眼一看就是错的；");
        System.out.println("  存成整数就没有这个问题，数值大小 = 真实的先后顺序。");
    }

    // ========================================================================
    // 第 3 段：查网段只要比大小 —— 不能用 varchar 的第二个理由
    // ========================================================================
    private static void demoRangeQuery(Connection conn) throws SQLException {
        System.out.println("\n---------- 第 3 段：查网段只要比大小，还能走索引 ----------");
        System.out.println("SQL：WHERE ip_num BETWEEN INET_ATON('192.168.1.0') AND INET_ATON('192.168.1.255')");

        printQuery(conn, """
                SELECT INET_NTOA(ip_num) AS ip FROM user_log
                WHERE ip_num BETWEEN INET_ATON('192.168.1.0') AND INET_ATON('192.168.1.255')
                """);

        System.out.println("EXPLAIN 看执行计划（type 应该是 range，key 是 idx_ip_num）：");
        printQuery(conn, """
                EXPLAIN SELECT * FROM user_log
                WHERE ip_num BETWEEN INET_ATON('192.168.1.0') AND INET_ATON('192.168.1.255')
                """);

        System.out.println("SHOW INDEX 确认索引真的建在 ip_num 上：");
        printQuery(conn, """
                SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM information_schema.statistics
                WHERE table_schema = 'demo' AND table_name = 'user_log'
                """);
        System.out.println("→ 存字符串只能拼 LIKE '192.168.1.%'，前缀匹配勉强能用索引，");
        System.out.println("  但像 192.168.0.0/16 这种跨段的范围查询，就比不出整数区间那么干脆了。");
    }

    // ========================================================================
    // 第 4 段：IPv6 存成 16 字节二进制
    //   IPv6 是 128 位，整数装不下，所以用 VARBINARY(16)，函数换成 INET6_ATON / INET6_NTOA。
    // ========================================================================
    private static void demoIpv6Table(Connection conn) throws SQLException {
        System.out.println("\n---------- 第 4 段：IPv6 存成 VARBINARY(16)（16 字节） ----------");

        execute(conn, "DROP TABLE IF EXISTS login_log");
        execute(conn, """
                CREATE TABLE login_log (
                  id     BIGINT UNSIGNED PRIMARY KEY,
                  ip_bin VARBINARY(16) NOT NULL,  -- 128 位 = 16 字节，不多不少
                  KEY idx_ip_bin (ip_bin)
                ) ENGINE=InnoDB
                """);
        execute(conn, """
                INSERT INTO login_log (id, ip_bin) VALUES
                  (1, INET6_ATON('2001:db8::1')),
                  (2, INET6_ATON('2001:db8::2'))
                """);
        printQuery(conn, """
                SELECT id, HEX(ip_bin) AS ip_hex, LENGTH(ip_bin) AS bytes, INET6_NTOA(ip_bin) AS ip
                FROM login_log
                """);
        System.out.println("→ LENGTH 正好 16，说明 VARBINARY(16) 一个字节都不浪费；");
        System.out.println("  INET6_ATON 对 IPv4 地址同样适用，所以 v4/v6 可以统一到一张表里处理。");
    }

    // ========================================================================
    // 小工具
    // ========================================================================

    /** 执行一条 DDL / DML。 */
    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 跑一条查询，把每行按「列名=值」打印出来。 */
    private static void printQuery(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData meta = resultSet.getMetaData();
            while (resultSet.next()) {
                StringBuilder row = new StringBuilder("   ");
                for (int column = 1; column <= meta.getColumnCount(); column++) {
                    row.append(meta.getColumnLabel(column)).append('=')
                            .append(resultSet.getString(column)).append("  ");
                }
                System.out.println(row);
            }
        }
    }
}
