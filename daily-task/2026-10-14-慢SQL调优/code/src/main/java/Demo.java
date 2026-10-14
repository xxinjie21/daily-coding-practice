/**
 * MySQL SQL 调优演示：一条 SQL 从「慢」到「快」的完整排查链路。
 *
 * 演示五步（对应原题思路）：
 *   1) 开慢查询日志，把慢 SQL 捞出来 —— 调优的第一步永远是「先知道慢在哪」
 *   2) EXPLAIN 看执行计划：type 从 ALL（全表扫描）变成 range（走索引）
 *   3) 最左前缀原则：跳过联合索引的左列，索引直接失效
 *   4) 覆盖索引：让排序也走索引，Extra 里的 Using filesort 消失
 *   5) 热点数据上 Redis 缓存，别让同样的查询反复打到数据库
 *
 * 前置条件：先在 code/ 目录下执行 `docker compose up -d`
 *   起 MySQL(127.0.0.1:3307, root/root, 库 demo) 与 Redis(127.0.0.1:6380)
 */
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Random;
import redis.clients.jedis.Jedis;

public class Demo {

    // rewriteBatchedStatements=true：让 JDBC 把批量插入合并成一条 INSERT，造数才够快
    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3307/demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&serverTimezone=Asia/Shanghai";
    static final String DB_USER = "root";
    static final String DB_PASSWORD = "root";
    static final String REDIS_HOST = "127.0.0.1";
    static final int REDIS_PORT = 6380;

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
            buildTable(conn);
            step1SlowQueryLog(conn);
            step2Explain(conn);
            step3LeftmostPrefix(conn);
            step4CoveringIndex(conn);
            step5RedisCache(conn);
        }
    }

    /** 建一张订单表并灌入 10 万行数据，后面的执行计划对比都基于它。 */
    static void buildTable(Connection conn) throws SQLException {
        System.out.println("=== 准备数据：orders 表 + 10 万行订单 ===");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("""
                    CREATE TABLE orders (
                      id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                      user_id     INT NOT NULL,
                      amount      DECIMAL(10,2) NOT NULL,
                      status      TINYINT NOT NULL,
                      remark      VARCHAR(200) NOT NULL,
                      create_time DATETIME NOT NULL
                    )""");
        }
        String insert = "INSERT INTO orders(user_id, amount, status, remark, create_time) VALUES (?,?,?,?,?)";
        Random random = new Random(42);
        LocalDateTime base = LocalDateTime.of(2023, 1, 1, 0, 0);
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (int i = 1; i <= 100_000; i++) {
                ps.setInt(1, random.nextInt(1000) + 1);                 // 1000 个用户，每人约 100 单
                ps.setDouble(2, 10 + random.nextDouble() * 990);
                ps.setInt(3, random.nextInt(4));
                ps.setString(4, "order-remark-" + i);
                ps.setTimestamp(5, Timestamp.valueOf(base.plusDays(i % 730).plusMinutes(random.nextInt(1440))));
                ps.addBatch();
                if (i % 1000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        System.out.println("造数完成：10 万行。\n");
    }

    /** 第 1 步：开慢查询日志，跑一条没索引的 SQL，再去慢日志表里把它捞出来。 */
    static void step1SlowQueryLog(Connection conn) throws SQLException {
        System.out.println("=== 第 1 步：慢查询日志，先知道「慢在哪」 ===");
        try (Statement st = conn.createStatement()) {
            st.execute("SET GLOBAL slow_query_log = ON");
            st.execute("SET GLOBAL log_output = 'TABLE'");   // 写进 mysql.slow_log 表，方便用 SQL 读
            // long_query_time 同时有全局和会话两个作用域，判定用的是当前会话的值，
            // 所以只 SET GLOBAL 不够，必须再 SET SESSION 一次，本连接的语句才会被记录
            st.execute("SET GLOBAL long_query_time = 0");
            st.execute("SET SESSION long_query_time = 0");
        }
        // remark 没有索引，LIKE '%...%' 又用不上索引，必然全表扫描
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT COUNT(*) FROM orders WHERE remark LIKE '%remark-999%'");
        }
        String sql = "SELECT sql_text, query_time, rows_examined FROM mysql.slow_log "
                + "WHERE start_time > NOW() - INTERVAL 30 SECOND ORDER BY start_time DESC LIMIT 3";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println("  慢SQL: " + oneLine(rs.getString("sql_text"))
                        + " | 耗时=" + rs.getString("query_time")
                        + " | 扫描行数=" + rs.getLong("rows_examined"));
            }
        }
        System.out.println();
    }

    /** 第 2 步：EXPLAIN 对比加索引前后的执行计划。 */
    static void step2Explain(Connection conn) throws SQLException {
        System.out.println("=== 第 2 步：EXPLAIN 看执行计划 ===");
        String sql = "SELECT * FROM orders WHERE user_id = 123 AND create_time > '2023-06-01'";
        System.out.println("  SQL: " + sql);
        explain(conn, "加索引前", sql);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX idx_user_time ON orders(user_id, create_time)");
        }
        explain(conn, "加索引后", sql);
        System.out.println("  说明：type=ALL 是全表扫描，type=range 是走了索引范围；rows 是预估扫描行数，越小越好。\n");
    }

    /** 第 3 步：最左前缀原则——联合索引 (user_id, create_time) 少了左边那列就用不上。 */
    static void step3LeftmostPrefix(Connection conn) throws SQLException {
        System.out.println("=== 第 3 步：最左前缀原则 ===");
        explain(conn, "带 user_id（左列在，走索引）", "SELECT * FROM orders WHERE user_id = 123");
        explain(conn, "只带 create_time（跳过左列，索引失效）",
                "SELECT * FROM orders WHERE create_time > '2023-06-01'");
        System.out.println("  说明：联合索引像按「姓+名」排序的电话簿，只报名字是查不动的，必须先有姓。\n");
    }

    /** 第 4 步：覆盖索引——让排序也走索引，消除 Extra 里的 Using filesort。 */
    static void step4CoveringIndex(Connection conn) throws SQLException {
        System.out.println("=== 第 4 步：ORDER BY 与覆盖索引 ===");
        explain(conn, "按 amount 排序（索引里没有它，要额外排序）",
                "SELECT id, amount FROM orders WHERE user_id = 123 ORDER BY amount");
        explain(conn, "只取索引里的列并按 create_time 排序（覆盖索引，排序也省了）",
                "SELECT user_id, create_time FROM orders WHERE user_id = 123 ORDER BY create_time");
        System.out.println("  说明：Extra 出现 Using filesort 就是「额外排了一次序」，能用索引顺序就让它消失。\n");
    }

    /** 第 5 步：热点查询结果放进 Redis，避免同样的查询反复打到数据库。 */
    static void step5RedisCache(Connection conn) throws SQLException {
        System.out.println("=== 第 5 步：热点数据上 Redis 缓存 ===");
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            String cacheKey = "order:count:user:123";
            jedis.del(cacheKey);

            // 第一次：缓存没命中，回源查库，再把结果写回缓存（SETEX = 带过期时间写入）
            long start = System.nanoTime();
            long count = queryOrderCount(conn, 123);
            jedis.setex(cacheKey, 60, String.valueOf(count));
            long dbCostMicros = (System.nanoTime() - start) / 1000;

            // 第二次：直接读缓存，数据库一点压力都没有
            start = System.nanoTime();
            String cached = jedis.get(cacheKey);
            long cacheCostMicros = (System.nanoTime() - start) / 1000;

            System.out.println("  第一次（缓存未命中，回源数据库）=" + dbCostMicros + "μs, 结果=" + count);
            System.out.println("  第二次（命中 Redis）=" + cacheCostMicros + "μs, 结果=" + cached
                    + ", TTL=" + jedis.ttl(cacheKey) + "s");
        }
        System.out.println("  说明：单次查询差距未必夸张，但高并发下能省掉大量重复的解析+执行，这才是缓存的价值。");
    }

    /** 执行 EXPLAIN 并打印调优最关心的四个字段。 */
    static void explain(Connection conn, String tag, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            rs.next();
            System.out.println("  [" + tag + "] type=" + rs.getString("type")
                    + ", key=" + rs.getString("key")
                    + ", rows=" + rs.getLong("rows")
                    + ", Extra=" + rs.getString("Extra"));
        }
    }

    static long queryOrderCount(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 慢日志里的 sql_text 是多行文本，压成一行便于打印。 */
    static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
