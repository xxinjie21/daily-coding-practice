/*
 * MySQL 索引失效排查：加了索引为什么还是慢
 * ----------------------------------------------------------------------------
 * 用真实的 MySQL（JDBC 连 localhost:3306/demo）建一张 20 万行的订单表，建好三个索引：
 *   idx_create_time (create_time)、idx_phone (phone)、idx_user_time (user_id, create_time)
 * 然后把「索引白建了」的四种写法逐个跑一遍，用 EXPLAIN 的 type / key / rows / Extra 说话。
 *
 *   实验一：在索引字段上做函数运算          YEAR(create_time) = 2023
 *   实验二：隐式类型转换                    varchar 字段用数字去查
 *   实验三：联合索引的最左前缀原则          (user_id, create_time) 只查 create_time 用不上
 *   实验四：LIKE 以 % 开头                  '%2345' vs '13800012%'
 *   实验五：读懂 Extra                      覆盖索引 / 回表 / Using filesort
 *   实验六：优化器主动放弃索引              小表、或命中比例很高时全表扫反而更快
 *   实验七：线上怎么捞坏 SQL                performance_schema 按总耗时排序（pt-query-digest 的原生版）
 *
 * 前置条件：本机 MySQL 8（见同目录 docker-compose.yml），root/root，库 demo。
 * 启动时会 DROP 重建 orders / orders_small 并灌入 20 万行。
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final String[] CITIES = {"杭州", "北京", "上海", "广州", "深圳"};

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            prepareData(conn);
            experiment1_functionOnIndexedColumn(conn);
            experiment2_implicitTypeConversion(conn);
            experiment3_leftmostPrefix(conn);
            experiment4_likeWildcard(conn);
            experiment5_readExtra(conn);
            experiment6_optimizerGivesUpIndex(conn);
            experiment7_slowQueryDigest(conn);
        }
        System.out.println("\n=== 一句话结论 ===");
        System.out.println("索引失效的共同点只有一个：把索引赖以排序的【原始值】弄没了，B+ 树就没法定位。");
        System.out.println("排查三板斧：EXPLAIN 看 type+key+Extra -> 慢日志捞出坏 SQL -> 按总耗时排优先级。");
    }

    /** 建 20 万行订单表 + 一个小表，并把三个索引建好。 */
    static void prepareData(Connection conn) throws SQLException {
        title("准备数据：orders 20 万行 + orders_small 200 行");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("""
                    CREATE TABLE orders (
                      id BIGINT PRIMARY KEY,
                      user_id BIGINT,
                      create_time DATETIME,
                      phone VARCHAR(20),
                      city VARCHAR(16),
                      KEY idx_create_time (create_time),
                      KEY idx_phone (phone),
                      KEY idx_user_time (user_id, create_time)
                    )""");
            st.execute("DROP TABLE IF EXISTS orders_small");
            st.execute("""
                    CREATE TABLE orders_small (
                      id BIGINT PRIMARY KEY,
                      user_id BIGINT,
                      KEY idx_user (user_id)
                    )""");
        }
        conn.setAutoCommit(false);
        // 每行往后挪 26 分钟，20 万行正好铺满约 10 年，方便按年份做区间查询
        LocalDateTime baseTime = LocalDateTime.of(2016, 1, 1, 0, 0);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders(id, user_id, create_time, phone, city) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 200_000; i++) {
                ps.setLong(1, i);
                ps.setLong(2, i % 500);
                ps.setObject(3, baseTime.plusMinutes((long) i * 26));
                ps.setString(4, "138" + String.format("%08d", i));
                ps.setString(5, CITIES[i % CITIES.length]);
                ps.addBatch();
                if (i % 5000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders_small(id, user_id) VALUES (?, ?)")) {
            for (int i = 0; i < 200; i++) {
                ps.setLong(1, i);
                ps.setLong(2, i % 5);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    // 实验一：字段上套函数，索引就白建了
    static void experiment1_functionOnIndexedColumn(Connection conn) throws SQLException {
        title("实验一：在索引字段上做函数运算");
        System.out.println("索引按 create_time 的【原始时间值】排序，套一层 YEAR() 等于要求「先算年份再比大小」，");
        System.out.println("而索引里根本没存年份这一列，只能把 20 万行全捞出来挨个算。");
        runAndExplain(conn, "坏：YEAR(create_time) = 2023",
                "SELECT * FROM orders WHERE YEAR(create_time) = 2023");
        runAndExplain(conn, "好：改写成时间区间",
                "SELECT * FROM orders WHERE create_time >= '2023-01-01' AND create_time < '2024-01-01'");
        System.out.println("--> 改法很固定：把函数从字段那一侧挪走，改写成一个原始值的区间。");
    }

    // 实验二：隐式类型转换（最坑，因为看不出来）
    static void experiment2_implicitTypeConversion(Connection conn) throws SQLException {
        title("实验二：隐式类型转换（最坑，因为看不出来）");
        System.out.println("phone 是 varchar，条件却给了个裸数字。MySQL 的规矩是「字符串和数字比较时把字符串转成数字」，");
        System.out.println("于是这句等价于 WHERE CAST(phone AS SIGNED) = 13800012345 —— 又变回了在字段上做函数。");
        runAndExplain(conn, "坏：phone = 13800012345（漏引号）",
                "SELECT * FROM orders WHERE phone = 13800012345");
        runAndExplain(conn, "好：phone = '13800012345'（加引号）",
                "SELECT * FROM orders WHERE phone = '13800012345'");
        System.out.println("--> Java 里手机号用 Long 存、拼 SQL 时漏了引号，本地数据少看不出来，上线就炸。");
    }

    // 实验三：联合索引的最左前缀
    static void experiment3_leftmostPrefix(Connection conn) throws SQLException {
        title("实验三：联合索引的最左前缀原则 idx_user_time (user_id, create_time)");
        System.out.println("联合索引像一本按「省 -> 市 -> 姓名」排的电话簿：只知道市名，你不知道该翻到哪一页。");
        System.out.println("（本实验临时删掉单列的 idx_create_time，假装它不存在）");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE orders DROP INDEX idx_create_time");
        }
        runAndExplain(conn, "坏：跳过最左列，只给 create_time",
                "SELECT * FROM orders WHERE create_time >= '2025-06-01' AND create_time < '2025-07-01'");
        runAndExplain(conn, "好A：只给最左列 user_id",
                "SELECT * FROM orders WHERE user_id = 42");
        runAndExplain(conn, "好B：两列都给",
                "SELECT * FROM orders WHERE user_id = 42 AND create_time >= '2025-06-01' AND create_time < '2025-07-01'");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE orders ADD INDEX idx_create_time (create_time)");
        }
        System.out.println("--> 真正致命的是【缺列】，不是【写反】：WHERE create_time = ? AND user_id = ? 优化器会自动调整顺序。");
    }

    // 实验四：LIKE 通配符的位置
    static void experiment4_likeWildcard(Connection conn) throws SQLException {
        title("实验四：LIKE 通配符的位置");
        System.out.println("拼音表按【开头】排序：你说「找拼音以 ping 开头的字」能定位，「以 ing 结尾的字」只能一页页翻。");
        runAndExplain(conn, "坏：LIKE '%2345'", "SELECT * FROM orders WHERE phone LIKE '%2345'");
        runAndExplain(conn, "好：LIKE '13800012%'", "SELECT * FROM orders WHERE phone LIKE '13800012%'");
        System.out.println("--> '13800012%' 能走索引，是因为它等价于 phone >= '13800012' AND phone < '13800013'，");
        System.out.println("    正好是索引里连续的一段。真要「包含匹配」，别硬扛，上 Elasticsearch。");
    }

    // 实验五：读懂 Extra
    static void experiment5_readExtra(Connection conn) throws SQLException {
        title("实验五：读懂 Extra —— Using index / 回表 / Using filesort");
        System.out.println("Using index（覆盖索引）：要的列索引里本来就存着，不用再回头去正文翻，最省。");
        runAndExplain(conn, "覆盖索引：只要 phone",
                "SELECT phone FROM orders WHERE phone LIKE '13800012%'");
        System.out.println("多要一个 city 列，索引里没有，就得拿着主键 id 再跑一趟主表 —— 这一步叫回表。");
        runAndExplain(conn, "回表：还要 city",
                "SELECT phone, city FROM orders WHERE phone LIKE '13800012%'");
        System.out.println("Using filesort 不是「用文件排序」，是「索引帮不上排序的忙，得把数据捞出来另外排一遍」。");
        runAndExplain(conn, "filesort：ORDER BY city",
                "SELECT * FROM orders WHERE user_id = 42 ORDER BY city");
    }

    // 实验六：优化器为什么主动放弃索引
    static void experiment6_optimizerGivesUpIndex(Connection conn) throws SQLException {
        title("实验六：加了索引，优化器却不用 —— 这种情况一般不用管");
        System.out.println("走索引 = 命中几行就要跳着读几次（还要回表），单次贵；全表扫 = 顺着读，单次便宜。优化器算完账谁便宜走谁。");
        runAndExplain(conn, "大表 + 命中很少（500 分之 1）",
                "SELECT * FROM orders WHERE user_id = 42");
        runAndExplain(conn, "大表 + 命中一半",
                "SELECT * FROM orders WHERE user_id < 250");
        runAndExplain(conn, "小表 200 行 + 命中 40 行",
                "SELECT * FROM orders_small WHERE user_id = 1");
        System.out.println("--> 后两条 key=NULL 是优化器算过账的结论，它是对的。真正要治的是「数据量大、命中少，key 却还是 NULL」。");
    }

    // 实验七：线上怎么把坏 SQL 捞出来
    static void experiment7_slowQueryDigest(Connection conn) throws SQLException {
        title("实验七：线上怎么把坏 SQL 捞出来 —— 按总耗时排序");
        System.out.println("pt-query-digest 的本质：把 SQL 参数抹成问号归成一类，再按【总耗时】倒序排。");
        System.out.println("MySQL 8 自带 performance_schema.events_statements_summary_by_digest 就是干这个的。");
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
            st.execute("SELECT * FROM orders WHERE YEAR(create_time) = 2023");
            st.execute("SELECT * FROM orders WHERE YEAR(create_time) = 2024");
            st.execute("SELECT * FROM orders WHERE phone LIKE '%2345'");
        }
        String sql = """
                SELECT DIGEST_TEXT, COUNT_STAR,
                       ROUND(SUM_TIMER_WAIT / 1000000) AS total_us,
                       ROUND(AVG_TIMER_WAIT / 1000000) AS avg_us
                FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME = 'demo' AND DIGEST_TEXT IS NOT NULL
                ORDER BY SUM_TIMER_WAIT DESC LIMIT 5""";
        System.out.printf("%-8s %-14s %-12s %s%n", "calls", "total(us)", "avg(us)", "SQL");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("%-8d %-14d %-12d %s%n",
                        rs.getLong("COUNT_STAR"), rs.getLong("total_us"), rs.getLong("avg_us"),
                        rs.getString("DIGEST_TEXT").replaceAll("\\s+", " "));
            }
        }
        System.out.println("--> 按总耗时排，先治榜首那条收益最大；一条单次 200ms 但一天只跑两回的 SQL，其实可以先放着。");
    }

    // ------------------------------------------------------------------
    // JDBC 小工具
    // ------------------------------------------------------------------

    /** 跑一条 SQL 并打印实测结果 + EXPLAIN 的四个关键列（就是原题说的排查重点）。 */
    static void runAndExplain(Connection conn, String label, String sql) throws SQLException {
        long startNano = System.nanoTime();
        int rows = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows++;
            }
        }
        long costMillis = (System.nanoTime() - startNano) / 1_000_000;

        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            if (rs.next()) {
                System.out.printf("[%s]%n", label);
                System.out.printf("  实测   -> 返回 %d 行，耗时 %d ms%n", rows, costMillis);
                System.out.printf("  EXPLAIN-> type=%-6s key=%-15s rows=%-9s Extra=%s%n",
                        rs.getString("type"), rs.getString("key"), rs.getString("rows"), rs.getString("Extra"));
            }
        }
    }

    static void title(String text) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(text);
        System.out.println("======================================================================");
    }
}
