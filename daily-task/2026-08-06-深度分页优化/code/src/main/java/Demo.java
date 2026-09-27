/*
 * 深度分页：LIMIT 1000000, 20 为什么慢，怎么救
 * ----------------------------------------------------------------------------
 * 用真实的 MySQL（JDBC 连 localhost:3306/demo）演示四件事，每条 SQL 都配 EXPLAIN：
 *   实验一：LIMIT offset, size —— 翻得越深越慢，因为数据库只能从头一行行数、数完再丢掉
 *   实验二：游标分页 WHERE id > 上一页最后一个 id —— 翻到第 5 万页也只扫 20 行
 *   实验三：延迟关联（子查询先只取 id，再回表取完整行）—— 必须支持「跳到第 N 页」时的折中办法
 *   实验四：排序字段有重复值时的坑 —— 游标只带时间戳会漏数据，必须带上 (时间, id)
 *
 * 前置条件：本机 MySQL 8（见同目录 docker-compose.yml），root/root，库 demo 已存在。
 * 启动时会 DROP 重建 orders 表并灌 100 万行，真实跑起来要几十秒。
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final int ROW_COUNT = 1_000_000;

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            prepareOrders(conn);
            experiment1_offsetPagingIsSlow(conn);
            experiment2_cursorPaging(conn);
            experiment3_deferredJoin(conn);
            experiment4_duplicateSortValueTrap(conn);
        }
        System.out.println("\n=== 一句话结论 ===");
        System.out.println("能改成「加载更多」就用游标分页；必须跳页就用延迟关联；热点榜单直接扔 Redis ZSET。");
    }

    /** 建表 + 灌 100 万行。每行 id 往前跳 1~3，故意造出「主键有空洞」的真实情况。 */
    static void prepareOrders(Connection conn) throws SQLException {
        title("准备数据：重建 orders 表并灌入 100 万行");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("""
                    CREATE TABLE orders (
                      id BIGINT PRIMARY KEY,
                      buyer_name VARCHAR(32),
                      amount INT,
                      create_time BIGINT,
                      KEY idx_create_time (create_time)
                    )""");
        }
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders(id, buyer_name, amount, create_time) VALUES (?, ?, ?, ?)")) {
            long id = 1000;
            for (int i = 0; i < ROW_COUNT; i++) {
                id += 1 + (i % 3);
                ps.setLong(1, id);
                ps.setString(2, "买家" + (i % 10000));
                ps.setInt(3, 10 + (i % 500));
                ps.setLong(4, 1_700_000_000L + i);
                ps.addBatch();
                if (i % 5000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    // ------------------------------------------------------------------
    // 实验一：LIMIT offset, size 为什么越翻越慢
    // ------------------------------------------------------------------
    static void experiment1_offsetPagingIsSlow(Connection conn) throws SQLException {
        title("实验一：LIMIT offset, size —— 翻得越深越慢");
        System.out.println("offset 的含义是「跳过前面多少行」，但 MySQL 没有一步跳过去的能力，");
        System.out.println("它只能真的把前 offset 行读出来、再一行行丢掉。看 EXPLAIN 的 rows 列就是它的估算扫描量。");
        for (int offset : new int[]{0, 10_000, 500_000, ROW_COUNT - 20}) {
            runAndExplain(conn, "LIMIT " + offset + ", 20",
                    "SELECT * FROM orders ORDER BY id LIMIT " + offset + ", 20");
        }
        System.out.println("--> 最后一行为了拿 20 条数据，白白扫了近 100 万行，这就是深度分页的病根。");
    }

    // ------------------------------------------------------------------
    // 实验二：游标分页（主推方案）
    // ------------------------------------------------------------------
    static void experiment2_cursorPaging(Connection conn) throws SQLException {
        title("实验二：游标分页 WHERE id > 上一页最后一个 id");
        System.out.println("换个问法：不问「跳过多少行」，而是问「从哪个 id 之后开始」。");
        System.out.println("主键索引是有序的 B+ 树，找一个 id 就像查字典翻到某个字，直接定位，不用从 A 数起。");

        long cursorId = idAtOffset(conn, 999_960);
        System.out.println("游标值（上一页最后一条 id）：" + cursorId);
        runAndExplain(conn, "游标分页", "SELECT * FROM orders WHERE id > " + cursorId + " ORDER BY id LIMIT 20");

        System.out.println("模拟前端「加载更多」连点 3 次：");
        long movingCursor = 0;
        for (int page = 1; page <= 3; page++) {
            List<Long> ids = queryIdsAfterCursor(conn, movingCursor, 5);
            System.out.println("  第 " + page + " 批：" + ids);
            movingCursor = ids.get(ids.size() - 1);
        }
        System.out.println("【易错点】主键常有空洞（删过数据、或用了雪花 id），所以千万别用「页码 × 每页条数」去算 id：");
        System.out.printf("  第 1 行 id=%d，第 21 行 id=%d，第 41 行 id=%d —— 明显不是 1、21、41%n",
                idAtOffset(conn, 0), idAtOffset(conn, 20), idAtOffset(conn, 40));
        System.out.println("  唯一正确的做法：把上一页真实的最后一条 id 带回来当游标。");
    }

    // ------------------------------------------------------------------
    // 实验三：延迟关联
    // ------------------------------------------------------------------
    static void experiment3_deferredJoin(Connection conn) throws SQLException {
        title("实验三：延迟关联 —— 产品经理非要「跳到第 N 页」怎么办");
        System.out.println("先只在索引上扫 id（索引很窄，扫起来快），拿到这 20 个 id 之后再回表取完整行。");
        System.out.println("回表 = 索引里只记了 id 和排序字段，其他字段得再跑一趟主表取，像去仓库提货。");
        runAndExplain(conn, "普通 LIMIT offset",
                "SELECT * FROM orders ORDER BY id LIMIT 999980, 20");
        runAndExplain(conn, "延迟关联",
                "SELECT o.* FROM orders o JOIN (SELECT id FROM orders ORDER BY id LIMIT 999980, 20) t ON o.id = t.id");
        System.out.println("--> 结果一模一样，但回表次数从 100 万降到 20。");
        System.out.println("    注意它只是「少搬货」，索引上那 100 万行还是要数一遍，数据量再涨还是会慢，属于次优解。");
    }

    // ------------------------------------------------------------------
    // 实验四：排序字段有重复值时，游标必须带上 id
    // ------------------------------------------------------------------
    static void experiment4_duplicateSortValueTrap(Connection conn) throws SQLException {
        title("实验四：按创建时间排序时的隐藏坑");
        System.out.println("同一秒可能下了好几单，create_time 一模一样。游标只带时间戳的话，");
        System.out.println("和边界同一时刻的那些订单会被整批跨过去 —— 数据凭空消失，还不报错。");

        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders_tie");
            st.execute("CREATE TABLE orders_tie (id BIGINT PRIMARY KEY, create_time BIGINT)");
            st.execute("INSERT INTO orders_tie VALUES (101,1000),(102,1000),(103,1000),(104,2000),(105,3000),(106,4000)");
        }
        System.out.println("表里 6 条订单，其中 id=101/102/103 的 create_time 都是 1000。每页取 2 条：");

        List<Long> wrong = pagingWithCursor(conn, false);
        System.out.println("【错误写法】WHERE create_time > 上一页最后的时间 -> 只翻出 " + wrong.size()
                + " 条：" + wrong + "，id=103 被吃掉了");
        List<Long> right = pagingWithCursor(conn, true);
        System.out.println("【正确写法】WHERE create_time > ? OR (create_time = ? AND id > ?) -> 翻出 " + right.size()
                + " 条：" + right + "，一条不漏");
    }

    /** 模拟连续翻页，返回翻出来的全部 id。cursorUsesId=false 表示游标只带时间戳，true 表示带上 (时间, id)。 */
    static List<Long> pagingWithCursor(Connection conn, boolean cursorUsesId) throws SQLException {
        List<Long> collected = new ArrayList<>();
        long lastTime = -1;
        long lastId = -1;
        for (int page = 0; page < 5; page++) {
            String where = cursorUsesId
                    ? "create_time > " + lastTime + " OR (create_time = " + lastTime + " AND id > " + lastId + ")"
                    : "create_time > " + lastTime;
            String sql = "SELECT id, create_time FROM orders_tie WHERE " + where
                    + " ORDER BY create_time, id LIMIT 2";
            List<long[]> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
                }
            }
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> collected.add(row[0]));
            lastTime = rows.get(rows.size() - 1)[1];
            lastId = rows.get(rows.size() - 1)[0];
        }
        return collected;
    }

    // ------------------------------------------------------------------
    // JDBC 小工具
    // ------------------------------------------------------------------

    /** 跑一条查询，返回 {耗时毫秒, 返回行数}。 */
    static long[] runQuery(Connection conn, String sql) throws SQLException {
        long startNano = System.nanoTime();
        int rows = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows++;
            }
        }
        return new long[]{(System.nanoTime() - startNano) / 1_000_000, rows};
    }

    /** 跑一条 SQL 并打印实测结果 + EXPLAIN 的四个关键列。 */
    static void runAndExplain(Connection conn, String label, String sql) throws SQLException {
        long[] result = runQuery(conn, sql);
        System.out.printf("[%s] 返回 %d 行，耗时 %d ms%n", label, result[1], result[0]);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            if (rs.next()) {
                System.out.printf("  EXPLAIN -> type=%s key=%s rows=%s Extra=%s%n",
                        rs.getString("type"), rs.getString("key"), rs.getString("rows"), rs.getString("Extra"));
            }
        }
    }

    static long idAtOffset(Connection conn, int offset) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM orders ORDER BY id LIMIT " + offset + ", 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static List<Long> queryIdsAfterCursor(Connection conn, long cursorId, int size) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM orders WHERE id > " + cursorId + " ORDER BY id LIMIT " + size;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    static void title(String text) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(text);
        System.out.println("======================================================================");
    }
}
