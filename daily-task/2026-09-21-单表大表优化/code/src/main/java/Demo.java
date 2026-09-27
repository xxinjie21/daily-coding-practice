// 【这个程序在干嘛】
// 题目：一张表塞了几千万行，查询越来越慢、写入也越来越慢，不拆库拆表怎么救？
// 用真实的 MySQL JDBC 把原题里最实用的几招跑一遍（每一步都真连库、真跑 SQL）：
//
//   实验 1  索引要精   —— EXPLAIN 对比：全表扫 type=ALL 和走索引 type=ref 差多少行
//   实验 2  覆盖索引   —— 查询要的列全在索引上时 Extra 显示 Using index，连回表都省了
//   实验 3  分批查询   —— where id > 上一批最大 id limit N，对比 LIMIT offset 的代价
//   实验 4  冷热分离   —— 老数据归档到历史表，主表只留热点，行数直接砍掉一大半
//   实验 5  在线加索引 —— ALGORITHM=INPLACE, LOCK=NONE，加索引期间表照样能读写
//
// 【前置条件】docker compose up -d mysql（localhost:3306，root/root，库名 demo）。
// 编译：mvn -o -q compile   运行：mvn -o exec:java

import java.sql.*;
import java.time.LocalDateTime;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final int TOTAL_ROWS = 200_000;
    static final int BATCH_SIZE = 1000;

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            System.out.println("========== 单表数据量大，不拆库拆表的优化演示 ==========");
            resetTables(conn);
            insertOrders(conn, TOTAL_ROWS);
            experimentOneIndex(conn);
            experimentTwoCoveringIndex(conn);
            experimentThreeCursorPaging(conn);
            experimentFourHotColdSplit(conn);
            experimentFiveOnlineAddIndex(conn);
            System.out.println("\n提醒：这几招都是让「数据搬运量」变少，是延长寿命不是根治；");
            System.out.println("      再往后就是读写分离（写主库、读从库，注意主从延迟）和分库分表。");
        }
        System.out.println("\n========== 全部实验结束 ==========");
    }

    // ===================== 建表 + 灌数据 =====================
    static void resetTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_archive");
            stmt.execute("DROP TABLE IF EXISTS orders");
            stmt.execute("""
                    CREATE TABLE orders (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      user_id INT NOT NULL,
                      amount DECIMAL(10,2) NOT NULL,
                      remark VARCHAR(200) NOT NULL,
                      created_at DATETIME NOT NULL
                    ) ENGINE=InnoDB
                    """);
        }
        System.out.println("已重建 orders 表（自增整型主键，暂时不建二级索引）");
    }

    static void insertOrders(Connection conn, int totalRows) throws SQLException {
        String sql = "INSERT INTO orders(user_id, amount, remark, created_at) VALUES (?, ?, ?, ?)";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 1; i <= totalRows; i++) {
                ps.setInt(1, i % 5000);                                          // 5000 个用户，每人 40 单
                ps.setDouble(2, 10 + i % 90);
                ps.setString(3, "备注" + i);
                ps.setTimestamp(4, Timestamp.valueOf(now.minusDays(i % 730)));    // 摊在最近两年
                ps.addBatch();
                if (i % BATCH_SIZE == 0) {
                    ps.executeBatch();   // 攒够一批再发，比一条一条插快得多
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
        System.out.printf("已插入 %,d 行订单数据%n", totalRows);
    }

    // ===================== 实验 1：索引要精 =====================
    static void experimentOneIndex(Connection conn) throws SQLException {
        System.out.println("\n---------- 实验 1：索引要精，别让查询全表扫 ----------");
        String sql = "SELECT id, user_id, amount FROM orders WHERE user_id = 7";

        System.out.println("   加索引前：" + explain(conn, sql));
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE orders ADD INDEX idx_user_id (user_id)");
        }
        System.out.println("   加索引后：" + explain(conn, sql));
        System.out.println("   EXPLAIN 的 rows 是预估要扫的行数：type=ALL 是全表翻一遍，type=ref 是走索引直接定位。");
    }

    // ===================== 实验 2：覆盖索引 =====================
    static void experimentTwoCoveringIndex(Connection conn) throws SQLException {
        System.out.println("\n---------- 实验 2：覆盖索引，连回表都省了 ----------");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE orders DROP INDEX idx_user_id, ADD INDEX idx_user_amount (user_id, amount)");
        }
        System.out.println("   只查索引里有的列：" + explain(conn,
                "SELECT user_id, amount FROM orders WHERE user_id = 7"));
        System.out.println("   还要查 remark  ：" + explain(conn,
                "SELECT user_id, amount, remark FROM orders WHERE user_id = 7"));
        System.out.println("   Extra 里出现 Using index 就是覆盖索引：要的列全在索引上，不用拿主键回表取整行。");
    }

    // ===================== 实验 3：分批（游标）查询 =====================
    static void experimentThreeCursorPaging(Connection conn) throws SQLException {
        System.out.println("\n---------- 实验 3：翻页别用大 offset，改用游标 ----------");
        int offset = 100_000;
        String byOffset = "SELECT id FROM orders ORDER BY id LIMIT " + offset + ", " + BATCH_SIZE;
        String byCursor = "SELECT id FROM orders WHERE id > " + offset + " ORDER BY id LIMIT " + BATCH_SIZE;

        long start = System.currentTimeMillis();
        long offsetRows = scalarLong(conn, "SELECT COUNT(*) FROM (" + byOffset + ") t");
        long offsetMillis = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        long cursorRows = scalarLong(conn, "SELECT COUNT(*) FROM (" + byCursor + ") t");
        long cursorMillis = System.currentTimeMillis() - start;

        System.out.printf("   LIMIT %d, %d -> 取到 %d 行，耗时 %d ms（前面 %d 行白扫）%n",
                offset, BATCH_SIZE, offsetRows, offsetMillis, offset);
        System.out.printf("   WHERE id > %d LIMIT %d -> 取到 %d 行，耗时 %d ms（走主键一步跳过去）%n",
                offset, BATCH_SIZE, cursorRows, cursorMillis);
        System.out.println("   两种写法取到的行完全一样，但 offset 越大，第一种越慢。");
    }

    // ===================== 实验 4：冷热数据分离 =====================
    static void experimentFourHotColdSplit(Connection conn) throws SQLException {
        System.out.println("\n---------- 实验 4：冷热数据分离，老数据归档 ----------");
        long beforeRows = scalarLong(conn, "SELECT COUNT(*) FROM orders");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders_archive LIKE orders");
            // 把一年前的订单搬去归档表：主表只留最近被频繁查询的热点数据
            stmt.executeUpdate("INSERT INTO orders_archive SELECT * FROM orders"
                    + " WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY)");
            stmt.executeUpdate("DELETE FROM orders WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY)");
        }
        long hotRows = scalarLong(conn, "SELECT COUNT(*) FROM orders");
        long archiveRows = scalarLong(conn, "SELECT COUNT(*) FROM orders_archive");
        System.out.printf("   归档前主表 %,d 行 -> 归档后主表 %,d 行，归档表 %,d 行%n", beforeRows, hotRows, archiveRows);
        System.out.printf("   日常查询只在 %,d 行里找，扫描量降到原来的 %.0f%%%n", hotRows, hotRows * 100.0 / beforeRows);
        System.out.println("   冷数据不是删掉，需要查历史时再去归档表 / 大数据平台里捞。");
    }

    // ===================== 实验 5：在线加索引 =====================
    static void experimentFiveOnlineAddIndex(Connection conn) throws SQLException {
        System.out.println("\n---------- 实验 5：在线加索引，别把写入堵死 ----------");
        try (Statement stmt = conn.createStatement()) {
            // ALGORITHM=INPLACE 原地改索引结构，LOCK=NONE 表示全程不阻塞读写
            stmt.execute("ALTER TABLE orders ADD INDEX idx_created_at (created_at), ALGORITHM=INPLACE, LOCK=NONE");
        }
        System.out.println("   已用 ALGORITHM=INPLACE, LOCK=NONE 加上 idx_created_at（加索引期间表照样能读写）");
        System.out.println("   不写这两个参数的话，大表加索引可能把写入堵住很久。当前索引清单：");
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SHOW INDEX FROM orders")) {
            while (rs.next()) {
                System.out.printf("   index=%-16s column=%-12s cardinality=%d%n",
                        rs.getString("Key_name"), rs.getString("Column_name"), rs.getLong("Cardinality"));
            }
        }
    }

    // ===================== 小工具 =====================
    /** 把 EXPLAIN 的结果压成一行：type / 用到的索引 / 预估扫描行数 / Extra 提示。 */
    static String explain(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
            if (!rs.next()) {
                return "(EXPLAIN 没有返回结果)";
            }
            return "type=" + rs.getString("type")
                    + " key=" + rs.getString("key")
                    + " rows=" + rs.getLong("rows")
                    + " Extra=" + rs.getString("Extra");
        }
    }

    /** 执行一条只返回一个数字的 SQL，比如 SELECT COUNT(*)。 */
    static long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
