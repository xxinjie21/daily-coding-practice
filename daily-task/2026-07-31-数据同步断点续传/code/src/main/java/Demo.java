// 数据同步断点续传 Demo（真实技术栈：MySQL + JDBC，真连真 SQL）
//
// 题目：把 A 库数据搬到 B 库，搬到一半程序挂了，重启后怎么既不漏数据、也不重复写？
// 答案两句话：记住搬到哪儿了（断点续传）+ 就算重复搬也不出错（幂等）。
//
// 演示的四个手段（全部落到真表、真 SQL）：
//   ① 状态追踪：进度写进 MySQL 的 sync_checkpoint 表，程序崩了它还在（不是放内存）。
//   ② 分批提交：每 1000 条一批、每批一个独立事务，失败最多丢一批。
//   ③ 幂等写入：目标库用 INSERT ... ON DUPLICATE KEY UPDATE（upsert），重复写不会多一行。
//   ④ 校验对账：同步完用 SQL 算两边的 checksum，对得上才算真的一致。
//
// 演示流程：
//   第 1 轮：同步跑到第 3 批时"程序崩溃"（模拟断电），目标库只有前两批
//   第 2 轮：重启，读进度表断点续传，把剩下的搬完
//   第 3 轮：故意全量重放，验证 upsert 幂等（行数不变）
//   最后：checksum 对账，确认源库和目标库完全一致
//
// 前置条件：本机起 MySQL(localhost:3306，root/root，库名 demo)，见 docker-compose.yml。
//           仅编译时无需启动数据库。

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
    static final String TASK_NAME = "order_sync";
    static final int BATCH_SIZE = 1000;
    static final int TOTAL_ROWS = 5000; // 源库一共 5000 条待同步数据

    /** 一行订单数据 */
    record Row(long orderNo, String status, int amount) {}

    /** 进度表里的一条记录：搬到哪儿了、上次什么状态 */
    record Checkpoint(long sourcePosition, String status) {}

    /** 用来模拟"同步任务跑到一半崩了" */
    static class SyncCrashException extends RuntimeException {
        SyncCrashException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            initData(conn);
            System.out.println("源库共有 " + count(conn, "orders") + " 条数据，每批 " + BATCH_SIZE + " 条同步。\n");

            // 第 1 轮：跑到第 3 批时崩溃
            System.out.println("【第 1 轮】正常启动，但会在第 3 批崩溃");
            try {
                runSync(conn, 3, false);
            } catch (SyncCrashException crash) {
                System.out.println("  ✗ 崩了：" + crash.getMessage());
            }
            System.out.println("  崩溃后目标库行数 = " + count(conn, "orders_sync") + "（预期 2000，即前两批）\n");

            // 第 2 轮：重启，断点续传
            System.out.println("【第 2 轮】重启任务，读进度表断点续传");
            runSync(conn, 0, false);
            System.out.println("  目标库行数 = " + count(conn, "orders_sync") + "（预期 " + TOTAL_ROWS + "）\n");

            // 第 3 轮：故意整个重放，验证幂等
            System.out.println("【第 3 轮】故意无视进度、把 5000 条全量重放一遍，验证 upsert 幂等");
            int beforeReplay = count(conn, "orders_sync");
            runSync(conn, 0, true);
            int afterReplay = count(conn, "orders_sync");
            System.out.println("  重放前行数 = " + beforeReplay + "，重放后行数 = " + afterReplay
                    + "（写了很多次，但行数没变多，这就是幂等）\n");

            // 对账
            System.out.println("【对账】比对源库与目标库的 checksum");
            long sourceChecksum = checksum(conn, "orders");
            long targetChecksum = checksum(conn, "orders_sync");
            System.out.println("  源库   checksum = " + sourceChecksum);
            System.out.println("  目标库 checksum = " + targetChecksum);

            boolean ok = beforeReplay == afterReplay && sourceChecksum == targetChecksum;
            System.out.println("\n校验结果：" + (ok
                    ? "全部通过：任务中途崩溃 + 重复重放，两边数据依然完全一致。"
                    : "未通过，请检查逻辑！"));
        }
    }

    // 建表 + 造数据：每次运行都从干净状态开始
    static void initData(Connection conn) throws SQLException {
        exec(conn, """
                CREATE TABLE IF NOT EXISTS orders (
                  order_no BIGINT PRIMARY KEY,
                  status   VARCHAR(16),
                  amount   INT
                )""");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS orders_sync (
                  order_no BIGINT PRIMARY KEY,
                  status   VARCHAR(16),
                  amount   INT
                )""");
        // 进度表：贴在门上的便利贴，记录"搬到第几本书了"。它在任务进程之外，所以程序崩了它还在。
        exec(conn, """
                CREATE TABLE IF NOT EXISTS sync_checkpoint (
                  task_name       VARCHAR(64) PRIMARY KEY,
                  source_position BIGINT,
                  status          VARCHAR(16),
                  update_time     DATETIME
                )""");
        exec(conn, "TRUNCATE TABLE orders");
        exec(conn, "TRUNCATE TABLE orders_sync");
        exec(conn, "TRUNCATE TABLE sync_checkpoint");

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (order_no, status, amount) VALUES (?, ?, ?)")) {
            for (long orderNo = 1; orderNo <= TOTAL_ROWS; orderNo++) {
                ps.setLong(1, orderNo);
                ps.setString(2, "PAID");
                ps.setInt(3, (int) (orderNo * 3 % 500));
                ps.addBatch();
                if (orderNo % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    /**
     * 跑一次同步。
     *
     * @param crashAtBatchNumber 跑到第几批时"人为崩溃"，传 0 表示不崩、一路跑完
     * @param forceRestartFromZero true = 无视进度从头再跑一遍（用来验证幂等）
     */
    static void runSync(Connection conn, int crashAtBatchNumber, boolean forceRestartFromZero)
            throws SQLException {
        Checkpoint checkpoint = loadCheckpoint(conn);
        long position = forceRestartFromZero ? 0L : checkpoint.sourcePosition();
        System.out.println("  启动前读进度表：上次状态=" + checkpoint.status() + "，已同步到 order_no=" + position);

        if (!forceRestartFromZero && "SUCCESS".equals(checkpoint.status())) {
            System.out.println("  上次已经跑成功了，本次无需重跑。");
            return;
        }

        conn.setAutoCommit(false);
        saveCheckpoint(conn, position, "RUNNING");
        conn.commit();

        int batchNumber = 0;
        while (true) {
            // 1) 从断点位置往后读一批：SELECT ... WHERE order_no > ? ORDER BY order_no LIMIT ?
            List<Row> batch = readBatch(conn, position, BATCH_SIZE);
            if (batch.isEmpty()) {
                break; // 没有更多数据，搬完了
            }
            batchNumber++;

            // 2) 本批独立事务写入目标库，全走 upsert（幂等）
            upsertBatch(conn, batch);

            // 3) 模拟"这一批写到一半崩了"：回滚本批（目标库一行没写），进度也不前进
            if (crashAtBatchNumber > 0 && batchNumber == crashAtBatchNumber) {
                conn.rollback();
                saveCheckpoint(conn, position, "FAILED");
                conn.commit();
                conn.setAutoCommit(true);
                throw new SyncCrashException("第 " + batchNumber + " 批写入时程序崩溃（模拟断电）");
            }

            // 4) 关键顺序：先写完目标库，再更新进度表。
            //    反过来的话，进度更新完就崩，这批会被静悄悄跳过 → 永久丢失。
            position = batch.get(batch.size() - 1).orderNo();
            saveCheckpoint(conn, position, "RUNNING");
            conn.commit();
            System.out.println("  第 " + batchNumber + " 批提交成功（" + batch.size()
                    + " 条），进度更新到 order_no=" + position);
        }

        saveCheckpoint(conn, position, "SUCCESS");
        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("  本轮同步完成，状态置为 SUCCESS。");
    }

    static List<Row> readBatch(Connection conn, long afterOrderNo, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT order_no, status, amount FROM orders WHERE order_no > ? ORDER BY order_no LIMIT ?")) {
            ps.setLong(1, afterOrderNo);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Row> batch = new ArrayList<>();
                while (rs.next()) {
                    batch.add(new Row(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
                return batch;
            }
        }
    }

    // upsert = 有则更新、无则插入，按主键兜底，重复数据不会多出一行
    static void upsertBatch(Connection conn, List<Row> batch) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders_sync (order_no, status, amount) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), amount = VALUES(amount)")) {
            for (Row row : batch) {
                ps.setLong(1, row.orderNo());
                ps.setString(2, row.status());
                ps.setInt(3, row.amount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static Checkpoint loadCheckpoint(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_position, status FROM sync_checkpoint WHERE task_name = ?")) {
            ps.setString(1, TASK_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Checkpoint(rs.getLong(1), rs.getString(2))
                        : new Checkpoint(0L, "NEVER_RUN");
            }
        }
    }

    static void saveCheckpoint(Connection conn, long position, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sync_checkpoint (task_name, source_position, status, update_time) "
                        + "VALUES (?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE "
                        + "source_position = VALUES(source_position), status = VALUES(status), update_time = NOW()")) {
            ps.setString(1, TASK_NAME);
            ps.setLong(2, position);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    // 对账用的 checksum：把一张表的数据揉成一个"指纹"数字，两边一样基本就认定内容一致
    static long checksum(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(SUM(order_no * 31 + amount + CRC32(status)), 0) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static int count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
