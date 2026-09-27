// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目：用 LIMIT offset, size 一页页搬数据（同步 / 导出）时，搬的过程中别人又插了数据，
//   会「丢数据」或「重复搬」。这里连真实的 MySQL，用真 SQL 复现这个坑，再用游标分页（cursor）修好它。
//   三个实验：
//     实验1：LIMIT 0,500 搬完第 1 页后，往【最前面】插一条，再 LIMIT 500,500 搬第 2 页 -> 丢 + 重。
//     实验2：游标分页（记住上一批最后一条的 (ts,id)），中途在【末尾】追加新数据 -> 不重不漏。
//     实验3：游标分页遇到【前面】插队的数据 -> 不会重复搬，数据不脏。
//
// 前置条件：本机有 MySQL。在本目录执行 `docker compose up -d` 起一个（localhost:3306，root/root，库 demo）。
//
// 生活比喻：offset 像「数第几本书」，前面塞进一本，后面全错位；
//           游标像「记住上本书长什么样，下次从它后面接着拿」，塞哪都不乱。

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASSWORD = "root";

    /** 每批搬多少条，对应真实同步里的 batch size。 */
    static final int PAGE_SIZE = 500;

    /** 一行数据：id 主键唯一，ts 是时间（可能重复），排序靠 (ts,id) 组合才唯一稳定。 */
    record Row(long id, long ts) {}

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            experimentOffsetLosesData(conn);
            experimentCursorCorrect(conn);
            experimentCursorNoDuplicate(conn);
        }
    }

    // ========== 实验1：offset 分页在「前面插数据」时会丢 + 重 ==========
    static void experimentOffsetLosesData(Connection conn) throws SQLException {
        System.out.println("========== 实验1：LIMIT offset 分页，搬第1页后前面插入一条 ==========");
        resetTable(conn, 1000);

        List<Long> got = new ArrayList<>();
        got.addAll(offsetPage(conn, 0));          // 第1页：LIMIT 0,500，拿到 id 1..500
        insertRow(conn, 9999, 0);                 // 别人往最前面塞一条（ts=0 排最前），整排书往后挪一位
        got.addAll(offsetPage(conn, PAGE_SIZE));  // 第2页：LIMIT 500,500，按新表执行

        System.out.println("两页合计 " + got.size() + " 条（源端应为 1001），去重后 " + new HashSet<>(got).size() + " 个不同 id");
        System.out.println("重复出现的 id：" + duplicates(got));
        System.out.println(">>> 结论：offset 分页遇到「前面插数据」会丢 + 重，数据对不上。\n");
    }

    // ========== 实验2：游标分页，中途在末尾正常追加，每条只搬一次 ==========
    static void experimentCursorCorrect(Connection conn) throws SQLException {
        System.out.println("========== 实验2：游标分页，中途在末尾追加新数据 ==========");
        resetTable(conn, 1000);
        explainCursorQuery(conn);

        List<Long> got = syncByCursor(conn, () -> {   // 搬完第1页后，新数据落到表末尾
            insertRow(conn, 1001, 1001);
            insertRow(conn, 1002, 1002);
        });
        System.out.println("游标分页共搬 " + got.size() + " 条，去重后 " + new HashSet<>(got).size()
                + " 条，重复 id：" + duplicates(got));
        System.out.println(">>> 结论：游标顺着 (ts,id) 接力，每批严格「比上一条更靠后」，新追加的也能捞到，不重不漏。\n");
    }

    // ========== 实验3：游标分页遇到前面插队，也不会重复搬 ==========
    static void experimentCursorNoDuplicate(Connection conn) throws SQLException {
        System.out.println("========== 实验3：游标分页，中途在前面插入乱序数据 ==========");
        resetTable(conn, 1000);

        List<Long> got = syncByCursor(conn, () -> insertRow(conn, 9999, 0)); // 往最前面插一条 ts=0
        System.out.println("游标分页 + 前面插数据：拿到 " + got.size() + " 条，去重后 " + new HashSet<>(got).size()
                + " 条，重复 id：" + duplicates(got));
        System.out.println(">>> 结论：游标遇到插队也『不会重复搬』，那条乱序数据会被本批跳过，等下次全量 / CDC 补，数据不脏。\n");
    }

    // ---------- 下面是 SQL 操作与工具方法 ----------

    /** 重建演示表并灌入 rowCount 条数据；idx_ts_id 让游标查询能走索引。 */
    static void resetTable(Connection conn, int rowCount) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_change");
            st.execute("CREATE TABLE t_change ("
                    + "id BIGINT PRIMARY KEY, ts BIGINT NOT NULL, data VARCHAR(64), "
                    + "KEY idx_ts_id (ts, id))");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t_change(id, ts, data) VALUES (?, ?, ?)")) {
            for (long i = 1; i <= rowCount; i++) {
                ps.setLong(1, i);
                ps.setLong(2, i);
                ps.setString(3, "d" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertRow(Connection conn, long id, long ts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t_change(id, ts, data) VALUES (?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, ts);
            ps.setString(3, "d" + id);
            ps.executeUpdate();
        }
    }

    /** offset 分页：SELECT ... ORDER BY ts, id LIMIT offset, size。 */
    static List<Long> offsetPage(Connection conn, int offset) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM t_change ORDER BY ts, id LIMIT ?, ?")) {
            ps.setInt(1, offset);
            ps.setInt(2, PAGE_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * 游标分页：取「(ts,id) 严格大于上一批最后一条」的前 PAGE_SIZE 行。
     * 这就是 WHERE (ts > ?) OR (ts = ? AND id > ?) ORDER BY ts, id LIMIT ? 的真实 SQL。
     * 注意条件必须拼上 id：ts 会重复，光比 ts 会漏也会重。
     */
    static List<Row> cursorPage(Connection conn, Row cursor) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts FROM t_change WHERE ts > ? OR (ts = ? AND id > ?) ORDER BY ts, id LIMIT ?")) {
            ps.setLong(1, cursor.ts());
            ps.setLong(2, cursor.ts());
            ps.setLong(3, cursor.id());
            ps.setInt(4, PAGE_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("id"), rs.getLong("ts")));
                }
            }
        }
        return rows;
    }

    /** 一页页搬到底：每批把最后一条当接力棒传给下一批。afterFirstBatch 用来模拟「搬到一半别人插数据」。 */
    static List<Long> syncByCursor(Connection conn, SqlAction afterFirstBatch) throws SQLException {
        Row cursor = new Row(0, 0);   // 初始游标：比所有数据都小
        List<Long> got = new ArrayList<>();
        boolean first = true;
        while (true) {
            List<Row> page = cursorPage(conn, cursor);
            if (page.isEmpty()) {
                break;
            }
            for (Row r : page) {
                got.add(r.id());
            }
            if (first) {
                afterFirstBatch.run();
                first = false;
            }
            cursor = page.get(page.size() - 1);
        }
        return got;
    }

    /** 打印 EXPLAIN，证明游标查询命中 idx_ts_id，不用全表扫。 */
    static void explainCursorQuery(Connection conn) throws SQLException {
        String sql = "EXPLAIN SELECT id, ts FROM t_change "
                + "WHERE ts > 500 OR (ts = 500 AND id > 500) ORDER BY ts, id LIMIT 500";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            System.out.println("  EXPLAIN 游标查询：key=" + rs.getString("key")
                    + "，扫描行数=" + rs.getLong("rows") + "（命中索引，不用全表扫）");
        }
    }

    /** 找出被搬了两次的 id。 */
    static Set<Long> duplicates(List<Long> ids) {
        Set<Long> seen = new HashSet<>();
        Set<Long> dup = new HashSet<>();
        for (Long id : ids) {
            if (!seen.add(id)) {
                dup.add(id);
            }
        }
        return dup;
    }

    /** 允许抛 SQLException 的小动作，用来在搬到一半时模拟别人插数据。 */
    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }
}
