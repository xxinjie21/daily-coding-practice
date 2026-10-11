import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示「数据库不停服迁移」：老库一边跑业务，一边把数据平滑搬到新库，全程业务无感。
 *
 * 前置：先 cd 到 code/ 目录执行 docker compose up -d，起好 MySQL（宿主机 3307，root/root）。
 *       老库用 demo，影子新库用 demo_new，两个库在同一个 MySQL 实例里（真实跨 schema 查询）。
 *
 * 代码严格按原题给出的六个步骤走：
 *   ① 结构兼容改造   ② 开启双写   ③ 历史迁移 + 增量追平
 *   ④ 数据比对与修复  ⑤ 切读流量  ⑥ 下线双写
 *
 * 读流量在 ①~④ 阶段始终走老库，⑤ 之后才切到新库 —— 这就是「不停服」的关键。
 * 时间戳统一用固定基准值 BASE_TIME 加偏移，保证每次输出都一样，方便对照。
 */
public class Demo {

    static final String SERVER_URL = "jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String OLD_URL = "jdbc:mysql://127.0.0.1:3307/demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String NEW_URL = "jdbc:mysql://127.0.0.1:3307/demo_new?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String USER = "root";
    static final String PASSWORD = "root";

    /** 固定基准时间（毫秒），后续所有 update_time 都用它加偏移，保证输出可复现。 */
    static final long BASE_TIME = 1_700_000_000_000L;
    /** 增量同步的水位线：老库里 update_time 大于它的行，才算「迁移开始后新产生的增量」。 */
    static final long WATERMARK = BASE_TIME + 5;

    /** 一行用户数据，读出来用于比对和修复。 */
    record UserRow(long id, String name, String phone, int status, long updateTime) {}

    public static void main(String[] args) throws Exception {
        createDatabases();
        try (Connection oldDb = DriverManager.getConnection(OLD_URL, USER, PASSWORD);
             Connection newDb = DriverManager.getConnection(NEW_URL, USER, PASSWORD)) {

            step1PrepareSchema(oldDb, newDb);
            step2SeedOldData(oldDb);
            System.out.println("【双写前】读 1003 走老库 -> " + readUser(oldDb, 1003));

            step3DualWrite(oldDb, newDb);
            System.out.println("【双写中】读 1006 仍走老库 -> " + readUser(oldDb, 1006));

            step4Migrate(oldDb, newDb);
            step5CompareAndRepair(oldDb, newDb);

            System.out.println("== ⑤ 切读流量 ==");
            System.out.println("【切读后】读 1003 走新库 -> " + readUser(newDb, 1003));
            step6StopDualWrite(newDb);
        }
        System.out.println("\n迁移完成：业务全程没停过机。");
    }

    // ---------- ① 结构兼容改造 ----------

    /** 老库、新库的表结构必须能共存：新库多一个 email 字段，但要给默认值，否则双写会报错。 */
    static void step1PrepareSchema(Connection oldDb, Connection newDb) throws SQLException {
        System.out.println("== ① 结构兼容改造 ==");
        run(oldDb, "DROP TABLE IF EXISTS t_user");
        run(newDb, "DROP TABLE IF EXISTS t_user");
        // 老表：没有 email
        run(oldDb, """
                CREATE TABLE t_user (
                  id BIGINT PRIMARY KEY,
                  name VARCHAR(32) NOT NULL,
                  phone VARCHAR(20) NOT NULL,
                  status INT NOT NULL DEFAULT 0,
                  update_time BIGINT NOT NULL,
                  KEY idx_update_time (update_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // 新表：多了 email，且 NOT NULL DEFAULT '' —— 老代码不知道这个字段也不会写失败
        run(newDb, """
                CREATE TABLE t_user (
                  id BIGINT PRIMARY KEY,
                  name VARCHAR(32) NOT NULL,
                  phone VARCHAR(20) NOT NULL,
                  status INT NOT NULL DEFAULT 0,
                  update_time BIGINT NOT NULL,
                  email VARCHAR(64) NOT NULL DEFAULT '',
                  KEY idx_update_time (update_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        System.out.println("老表 5 列，新表 6 列（多 email，NOT NULL DEFAULT ''），双写不会因缺字段失败\n");
    }

    // ---------- ② 开启双写 ----------

    static void step2SeedOldData(Connection oldDb) throws SQLException {
        System.out.println("== 准备：老库预置 5 条历史数据 ==");
        for (int i = 1; i <= 5; i++) {
            long id = 1000 + i;
            upsert(oldDb, new UserRow(id, "user" + id, "1380000000" + i, 1, BASE_TIME + i), false);
        }
        System.out.println("老库现有 " + count(oldDb) + " 行\n");
    }

    /** 双写阶段：业务代码同时写老库和新库，读还是只读老库（新库此时是「影子库」）。 */
    static void step3DualWrite(Connection oldDb, Connection newDb) throws SQLException {
        System.out.println("== ② 开启双写 ==");
        writeBoth(oldDb, newDb, new UserRow(1006, "user1006", "13800001006", 1, BASE_TIME + 10));
        writeBoth(oldDb, newDb, new UserRow(1007, "user1007", "13800001007", 1, BASE_TIME + 11));

        // 模拟「双写走异步消息、消息丢了」：这一行只落到了老库，新库漏了 —— 后面靠增量同步补
        upsert(oldDb, new UserRow(1008, "user1008", "13800001008", 1, BASE_TIME + 12), false);
        System.out.println("老库 " + count(oldDb) + " 行，新库 " + count(newDb) + " 行（1008 只在老库）\n");
    }

    /**
     * 两个库分别提交，做不到跨库原子 —— 这正是「最怕中间出错导致数据不一致」的根源，
     * 所以后面必须有 ④ 比对修复 兜底。先写老库再写新库，任一失败就各自回滚。
     */
    static void writeBoth(Connection oldDb, Connection newDb, UserRow row) throws SQLException {
        oldDb.setAutoCommit(false);
        newDb.setAutoCommit(false);
        try {
            upsert(oldDb, row, false);
            upsert(newDb, row, true);
            oldDb.commit();
            newDb.commit();
        } catch (SQLException e) {
            oldDb.rollback();
            newDb.rollback();
            throw e;
        } finally {
            oldDb.setAutoCommit(true);
            newDb.setAutoCommit(true);
        }
    }

    // ---------- ③ 历史迁移 + 增量追平 ----------

    /** 先全量搬历史数据，再按 update_time 水位追增量（真实场景是 Canal 监听 binlog）。 */
    static void step4Migrate(Connection oldDb, Connection newDb) throws SQLException {
        System.out.println("== ③ 历史迁移 + 增量追平 ==");
        int history = 0;
        for (UserRow row : allRows(oldDb)) {          // 全量：老库所有行搬过去
            upsert(newDb, row, true);
            history++;
        }
        System.out.println("全量搬历史数据 " + history + " 行");

        int increment = 0;
        for (UserRow row : rowsAfter(oldDb, WATERMARK)) {  // 增量：水位之后新产生的行
            upsert(newDb, row, true);
            increment++;
        }
        System.out.println("追增量 " + increment + " 行（含只在老库的 1008），新库现有 " + count(newDb) + " 行\n");
    }

    // ---------- ④ 数据比对与修复 ----------

    static void step5CompareAndRepair(Connection oldDb, Connection newDb) throws SQLException {
        System.out.println("== ④ 数据比对与修复 ==");
        // 人为制造一条不一致：新库 1003 的 phone 和「双写时丢的一次更新」对不上
        run(newDb, "UPDATE t_user SET phone='13900000000' WHERE id=1003");
        System.out.println("比对校验：老库[" + checksum(oldDb) + "]  新库[" + checksum(newDb) + "]");

        List<Long> diff = diffIds(oldDb, newDb);
        System.out.println("查出不一致的行 id = " + diff);
        for (long id : diff) {                        // 用老库的值覆盖新库，把差异补齐
            upsert(newDb, readRow(oldDb, id), true);
        }
        System.out.println("修复后：老库[" + checksum(oldDb) + "]  新库[" + checksum(newDb) + "]");
        System.out.println("再次比对不一致行 = " + diffIds(oldDb, newDb) + "\n");
    }

    // ---------- ⑥ 下线双写 ----------

    /** 读稳定后关掉双写，只写新库，老库归档。 */
    static void step6StopDualWrite(Connection newDb) throws SQLException {
        System.out.println("== ⑥ 下线双写 ==");
        upsert(newDb, new UserRow(1009, "user1009", "13800001009", 1, BASE_TIME + 20), true);
        System.out.println("只写新库 1009，新库现有 " + count(newDb) + " 行，老库保持归档不再写入");
    }

    // ---------- 通用小工具 ----------

    /** 写入一行；withEmail 决定写不写新库独有的 email 列。存在同 id 就更新（幂等，方便重跑）。 */
    static void upsert(Connection db, UserRow row, boolean withEmail) throws SQLException {
        String sql = withEmail
                ? "INSERT INTO t_user(id,name,phone,status,update_time,email) VALUES(?,?,?,?,?,?) AS v "
                  + "ON DUPLICATE KEY UPDATE name=v.name,phone=v.phone,status=v.status,update_time=v.update_time"
                : "INSERT INTO t_user(id,name,phone,status,update_time) VALUES(?,?,?,?,?) AS v "
                  + "ON DUPLICATE KEY UPDATE name=v.name,phone=v.phone,status=v.status,update_time=v.update_time";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, row.id());
            ps.setString(2, row.name());
            ps.setString(3, row.phone());
            ps.setInt(4, row.status());
            ps.setLong(5, row.updateTime());
            if (withEmail) {
                ps.setString(6, row.name() + "@demo.com");
            }
            ps.executeUpdate();
        }
    }

    static String readUser(Connection db, long id) throws SQLException {
        UserRow r = readRow(db, id);
        return r == null ? "不存在" : r.name() + " / " + r.phone();
    }

    static UserRow readRow(Connection db, long id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id,name,phone,status,update_time FROM t_user WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5))
                        : null;
            }
        }
    }

    static List<UserRow> allRows(Connection db) throws SQLException {
        return query(db, "SELECT id,name,phone,status,update_time FROM t_user ORDER BY id");
    }

    static List<UserRow> rowsAfter(Connection db, long watermark) throws SQLException {
        return query(db, "SELECT id,name,phone,status,update_time FROM t_user WHERE update_time > "
                + watermark + " ORDER BY id");
    }

    static List<UserRow> query(Connection db, String sql) throws SQLException {
        List<UserRow> rows = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5)));
            }
        }
        return rows;
    }

    /** 抽样比对：行数 + 关键字段的 CRC32 校验和，两边一样就基本一致（真实场景用 DataDiff 抽样）。 */
    static String checksum(Connection db) throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) c, COALESCE(SUM(CRC32(CONCAT_WS('#',id,name,phone,status))),0) s FROM t_user")) {
            rs.next();
            return "行数=" + rs.getLong("c") + " 校验和=" + rs.getLong("s");
        }
    }

    /** 找出老库有、新库没有，或两边关键字段对不上的行（真实跨 schema JOIN）。 */
    static List<Long> diffIds(Connection oldDb, Connection newDb) throws SQLException {
        String sql = "SELECT o.id FROM demo.t_user o LEFT JOIN demo_new.t_user n ON o.id = n.id "
                + "WHERE n.id IS NULL OR n.name <> o.name OR n.phone <> o.phone OR n.status <> o.status "
                + "ORDER BY o.id";
        List<Long> ids = new ArrayList<>();
        try (Statement st = oldDb.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    static long count(Connection db) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t_user")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void run(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    /** 连到 MySQL 实例本身（不指定库），把两个库建出来。 */
    static void createDatabases() throws SQLException {
        try (Connection server = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement st = server.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS demo CHARACTER SET utf8mb4");
            st.execute("CREATE DATABASE IF NOT EXISTS demo_new CHARACTER SET utf8mb4");
        }
    }
}
