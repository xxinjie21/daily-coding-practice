// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   把 A 库的数据搬到 B 库，路上会塌方（网络断）、会车坏（下游挂）、还会有人把同一批货来回搬（循环复制）。
//   这里连真实的 MySQL，用真表真 SQL 演示 5 道容错保险：
//     ① 断点续传：位点（checkpoint）和数据在同一个事务里原子提交，崩了从最后确认的位点继续，不重不漏
//     ② 幂等写入：同一条变更重复投递，靠主键 + ON DUPLICATE KEY UPDATE 保证不产生重复行
//     ③ 重试 + 指数退避 + 死信队列：下游抖一下就温和重试，彻底坏就落 DLQ 表等人工
//     ④ 循环复制防护：每条变更带 sync-id，自己发出去又被绕回来的回声直接丢弃
//     ⑤ 对账补数：定期比对源表与目标表，缺的补上，这是最后一道防线
//
// 前置条件：本机有 MySQL。在本目录执行 `docker compose up -d` 起一个（localhost:3306，root/root，库 demo）。
//
// 生活比喻：北京仓往上海仓搬货。小本子记「搬到第几车」= 位点；
//           上海仓关门先把货堆中转仓 = 缓冲；门坏了就别猛拍 = 退避重试；彻底坏进问题货区 = DLQ。

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASSWORD = "root";

    static final int SOURCE_ROWS = 10;   // 源端一共 10 条变更（好比 binlog 里的 10 条改动）
    static final String JOB = "syncJob"; // 同步任务名，位点按它存
    static final String MY_NODE = "A";   // 本节点标识，写进 sync_id 用于循环复制防护
    static final int MAX_ATTEMPTS = 5;   // 最多重试 5 次

    record Msg(String syncId, String payload) {}

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            resetSchema(conn);
            experimentCheckpoint(conn);
            experimentIdempotentWrite(conn);
            experimentRetryWithDlq(conn);
            experimentSyncIdFilter(conn);
            experimentReconcile(conn);
        }
    }

    // ========== ① 断点续传：位点和数据一起原子提交 ==========
    static void experimentCheckpoint(Connection conn) throws SQLException {
        System.out.println("【机制1】断点续传：位点和数据同一事务提交，崩了从位点继续");
        conn.setAutoCommit(false);
        try {
            System.out.println("  第1次运行（跑到第5条崩）：");
            syncFromCheckpoint(conn);
            System.out.println("  --- 服务重启，继续跑 ---");
            syncFromCheckpoint(conn);
        } finally {
            conn.setAutoCommit(true); // 恢复默认，别影响后面的实验
        }
        System.out.println("  目标库共 " + countRows(conn, "sync_target") + " 条，与源端 "
                + SOURCE_ROWS + " 条一致：" + (countRows(conn, "sync_target") == SOURCE_ROWS));
        System.out.println();
    }

    /** 从位点继续读源端变更，每条「写目标 + 更新位点」一起提交，中途模拟崩溃。 */
    static void syncFromCheckpoint(Connection conn) throws SQLException {
        long from = readCheckpoint(conn, JOB);
        System.out.println("  从第 " + (from + 1) + " 条继续读");
        for (long id = from + 1; id <= SOURCE_ROWS; id++) {
            insertTarget(conn, id, "change#" + id, MY_NODE);
            updateCheckpoint(conn, JOB, id);
            conn.commit(); // 数据和位点一起原子提交：要么都成功，要么都不成功
            System.out.println("  同步第 " + id + " 条 -> 目标库，位点=" + id);
            if (id == 5) {
                System.out.println("  [崩在第 5 条后，但位点=5 已落库]");
                return;
            }
        }
    }

    // ========== ② 幂等写入：重复投递不产生重复行 ==========
    static void experimentIdempotentWrite(Connection conn) throws SQLException {
        System.out.println("【机制2】幂等写入：同一条变更重复投递，目标表行数不变");
        long before = countRows(conn, "sync_target");
        for (int replay = 0; replay < 2; replay++) {      // 模拟位点丢失后的重放
            for (long id = 1; id <= 3; id++) {
                insertTarget(conn, id, "change#" + id, MY_NODE);
            }
        }
        System.out.println("  重复投递 6 次后，目标表行数 " + before + " -> " + countRows(conn, "sync_target")
                + "（主键挡住重复，幂等生效）");
        System.out.println();
    }

    // ========== ③ 重试 + 指数退避 + 死信队列 ==========
    static void experimentRetryWithDlq(Connection conn) throws SQLException {
        System.out.println("【机制3】重试 + 指数退避 + 死信队列");
        System.out.println("  笔1（下游偶尔抖动）：");
        String r1 = deliverWithRetry(conn, 101, "change#101", 2);   // 前 2 次失败，第 3 次成功
        System.out.println("  结果：" + ("OK".equals(r1) ? "退避重试后成功" : "进死信队列"));

        System.out.println("  笔2（下游彻底坏）：");
        String r2 = deliverWithRetry(conn, 102, "change#102", Integer.MAX_VALUE);
        System.out.println("  结果：" + ("DLQ".equals(r2) ? "5 次都失败，进死信队列交人工" : "成功"));
        System.out.println("  死信队列现有 " + countRows(conn, "sync_dlq") + " 条");
        System.out.println();
    }

    /**
     * 投递一条变更：失败就退避重试，最多 MAX_ATTEMPTS 次；全失败就落 DLQ 表。
     * failTimes 表示「前几次会失败」，用来模拟下游抖动 / 彻底坏（真实系统里是抛异常或超时）。
     */
    static String deliverWithRetry(Connection conn, long id, String payload, int failTimes) throws SQLException {
        long backoffMillis = 100; // 真实是 1s 起，这里缩到 100ms，免得演示干等
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > failTimes) {
                insertTarget(conn, id, payload, MY_NODE);
                return "OK";
            }
            if (attempt < MAX_ATTEMPTS) {
                System.out.println("    第" + attempt + "次失败，退避 " + backoffMillis + "ms 后重试（不猛拍门）");
            }
            sleep(backoffMillis);
            backoffMillis *= 2; // 指数退避
        }
        insertDlq(conn, id, payload, "重试 " + MAX_ATTEMPTS + " 次仍失败");
        return "DLQ";
    }

    // ========== ④ 循环复制防护：sync-id 过滤自己的回声 ==========
    static void experimentSyncIdFilter(Connection conn) throws SQLException {
        System.out.println("【机制4】循环复制防护：带 sync-id，自己发出去的回声直接丢");
        // 收到的消息里混了 A 自己发出又被绕回来的货，必须丢弃，否则 A->B->A 会无限绕圈
        List<Msg> incoming = List.of(
                new Msg(MY_NODE, "change#201"),
                new Msg("B", "change#202"),
                new Msg(MY_NODE, "change#203"),
                new Msg("B", "change#204"));
        long id = 201;
        int applied = 0;
        for (Msg msg : incoming) {
            if (msg.syncId().equals(MY_NODE)) {
                System.out.println("  丢弃自己发过的 " + msg.payload() + "（防 A->B->A 绕圈）");
                continue;
            }
            insertTarget(conn, id++, msg.payload(), msg.syncId());
            applied++;
        }
        System.out.println("  A 实际落库（不含自己回声）：" + applied + " 条");
        System.out.println();
    }

    // ========== ⑤ 对账补数：最后一道防线 ==========
    static void experimentReconcile(Connection conn) throws SQLException {
        System.out.println("【机制5】对账补数：比对源表与目标表，缺的补上");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM sync_target WHERE id IN (7, 9)"); // 模拟同步静默遗漏
        }
        List<Long> missing = findMissingIds(conn);
        System.out.println("  对账发现目标端缺：" + missing);
        for (Long id : missing) {
            insertTarget(conn, id, "change#" + id, MY_NODE);
        }
        System.out.println("  补数后目标端已覆盖源端全部 " + SOURCE_ROWS + " 条："
                + findMissingIds(conn).isEmpty());
        System.out.println();
    }

    /** 源表有、目标表没有的 id。 */
    static List<Long> findMissingIds(Connection conn) throws SQLException {
        List<Long> missing = new ArrayList<>();
        String sql = "SELECT s.id FROM sync_source s LEFT JOIN sync_target t ON s.id = t.id WHERE t.id IS NULL";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                missing.add(rs.getLong(1));
            }
        }
        return missing;
    }

    // ---------- 建表与 SQL 小封装 ----------

    static void resetSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sync_source, sync_target, sync_checkpoint, sync_dlq");
            st.execute("CREATE TABLE sync_source (id BIGINT PRIMARY KEY, payload VARCHAR(64))");
            st.execute("CREATE TABLE sync_target (id BIGINT PRIMARY KEY, payload VARCHAR(64), sync_id VARCHAR(16))");
            st.execute("CREATE TABLE sync_checkpoint (job VARCHAR(64) PRIMARY KEY, pos BIGINT NOT NULL)");
            st.execute("CREATE TABLE sync_dlq (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "change_id BIGINT, payload VARCHAR(64), reason VARCHAR(128))");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_source(id, payload) VALUES (?, ?)")) {
            for (long i = 1; i <= SOURCE_ROWS; i++) {
                ps.setLong(1, i);
                ps.setString(2, "change#" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_checkpoint(job, pos) VALUES (?, 0)")) {
            ps.setString(1, JOB);
            ps.executeUpdate();
        }
    }

    /** 幂等写入：主键冲突时只更新内容，不会新增一行。 */
    static void insertTarget(Connection conn, long id, String payload, String syncId) throws SQLException {
        String sql = "INSERT INTO sync_target(id, payload, sync_id) VALUES (?, ?, ?) AS new "
                + "ON DUPLICATE KEY UPDATE payload = new.payload";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, payload);
            ps.setString(3, syncId);
            ps.executeUpdate();
        }
    }

    static void insertDlq(Connection conn, long changeId, String payload, String reason) throws SQLException {
        String sql = "INSERT INTO sync_dlq(change_id, payload, reason) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, changeId);
            ps.setString(2, payload);
            ps.setString(3, reason);
            ps.executeUpdate();
        }
    }

    static long readCheckpoint(Connection conn, String job) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pos FROM sync_checkpoint WHERE job = ?")) {
            ps.setString(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    static void updateCheckpoint(Connection conn, String job, long pos) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE sync_checkpoint SET pos = ? WHERE job = ?")) {
            ps.setLong(1, pos);
            ps.setString(2, job);
            ps.executeUpdate();
        }
    }

    /** 表名都是代码里的常量，不是外部输入，直接拼进 COUNT 查询是安全的。 */
    static long countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
