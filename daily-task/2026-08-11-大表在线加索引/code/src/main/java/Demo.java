import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 这个程序在干嘛？
 * 演示「给一亿行的大表加索引，怎么不把线上业务卡死」——用真 MySQL 真执行，不是纸上谈兵。
 *
 * 反面教材是直接 ALTER TABLE ADD INDEX：老版本 MySQL 会锁表，加索引这几十分钟里谁也别想读写。
 * 正解是 MySQL 5.6+ 的「在线 DDL」：加索引时显式带上 ALGORITHM=INPLACE, LOCK=NONE，
 * 建索引过程中原表照常读写，建完再用 SHOW INDEX / EXPLAIN 确认索引真的生效了。
 *
 * 前置条件：本机 3306 上有 MySQL（见 docker-compose.yml），账号 root/root，库名 demo。
 * 演示步骤：
 *   ① 建表 large_table 并灌入若干行（真实场景一亿行，这里用 5 万行，流程一模一样）
 *   ② 加索引前：EXPLAIN 按 user_id 查是全表扫描，SHOW INDEX 只有主键
 *   ③ 在线加索引：ALTER TABLE ... ADD INDEX idx_user(user_id), ALGORITHM=INPLACE, LOCK=NONE
 *   ④ 加索引后：SHOW INDEX 能看到 idx_user，EXPLAIN 的 type 从 ALL 变成 ref，查询走索引
 *
 * 补充：INPLACE 只是「不重建整张表」，大表建二级索引仍要扫一遍聚簇索引，该耗时还是耗时。
 * 所以生产环境的大表变更，业界更常用 gh-ost / pt-osc 的「影子表」方案（见题解.md）。
 */
public class Demo {

    static final String URL =
            "jdbc:mysql://localhost:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASSWORD = "root";
    // 真实大表约 1 亿行；这里灌 5 万行足够演示「全表扫描 vs 走索引」的差别
    static final int ROW_COUNT = 50_000;

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            prepareTable(conn);
            System.out.println("原表已有 " + ROW_COUNT + " 行（真实场景约 1 亿行），此时 user_id 上【没有索引】。");

            System.out.println("\n[加索引前] EXPLAIN SELECT * FROM large_table WHERE user_id = 17：");
            explainByUserId(conn);
            System.out.println("[加索引前] SHOW INDEX：");
            showIndex(conn);

            System.out.println("\n[正解] 在线加索引，业务不中断：");
            System.out.println("        ALTER TABLE large_table ADD INDEX idx_user(user_id), ALGORITHM=INPLACE, LOCK=NONE");
            long startMillis = System.currentTimeMillis();
            try (Statement st = conn.createStatement()) {
                // LOCK=NONE：允许加索引期间原表继续读写；ALGORITHM=INPLACE：不重建整张表
                st.executeUpdate("ALTER TABLE large_table ADD INDEX idx_user (user_id), ALGORITHM=INPLACE, LOCK=NONE");
            }
            System.out.println("        加索引完成，耗时 " + (System.currentTimeMillis() - startMillis)
                    + " ms（全程未锁表，线上读写不受影响）。");

            System.out.println("\n[加索引后] SHOW INDEX：");
            showIndex(conn);
            System.out.println("\n[加索引后] 再 EXPLAIN 一次：");
            explainByUserId(conn);

            System.out.println("\n提醒：索引不是越多越好——每多一个索引，写数据就慢一分；");
            System.out.println("      先拿慢查询日志找出最该加的那个，并且生产操作一定先在备库演练一遍。");
        }
    }

    /** 建一张「大表」并灌数据：DROP 重建是为了让演示可重复跑。 */
    static void prepareTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS large_table");
            st.executeUpdate("CREATE TABLE large_table ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "user_id BIGINT NOT NULL,"
                    + "amount INT NOT NULL,"
                    + "created_at DATETIME NOT NULL) ENGINE=InnoDB");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO large_table(user_id, amount, created_at) VALUES (?, ?, NOW())")) {
            for (int i = 1; i <= ROW_COUNT; i++) {
                ps.setLong(1, i % 1_000);      // user_id 分布在 0~999
                ps.setInt(2, 100 + (i % 500));
                ps.addBatch();
                if (i % 1_000 == 0) {
                    ps.executeBatch();         // 分批提交，避免一次攒 5 万条撑爆内存
                }
            }
            ps.executeBatch();
        }
    }

    /** 用 EXPLAIN 看这条查询走没走索引：type=ALL 是全表扫描，type=ref 才是走索引。 */
    static void explainByUserId(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("EXPLAIN SELECT * FROM large_table WHERE user_id = ?")) {
            ps.setLong(1, 17L);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.printf("        type=%-4s key=%-8s rows=%d%n",
                            rs.getString("type"),
                            String.valueOf(rs.getString("key")),
                            rs.getLong("rows"));
                }
            }
        }
    }

    /** 列出表上的二级索引（跳过主键），能看到索引名、列名和基数。 */
    static void showIndex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW INDEX FROM large_table")) {
            boolean hasSecondaryIndex = false;
            while (rs.next()) {
                String keyName = rs.getString("Key_name");
                if ("PRIMARY".equals(keyName)) {
                    continue;
                }
                hasSecondaryIndex = true;
                System.out.printf("        索引名=%-10s 列=%-8s 基数(cardinality)=%d%n",
                        keyName, rs.getString("Column_name"), rs.getLong("Cardinality"));
            }
            if (!hasSecondaryIndex) {
                System.out.println("        （除主键外没有任何二级索引）");
            }
        }
    }
}
