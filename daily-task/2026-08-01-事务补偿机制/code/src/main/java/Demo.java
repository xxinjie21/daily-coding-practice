// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   演示「分布式事务补偿机制」：下单 → 扣库存 → 扣余额，三步分属不同系统，没法一起回滚。
//   哪一步失败了，就把前面已经成功的步「反向补平」；补偿本身也可能失败，所以还得重试，
//   重试 3 次仍不行就转人工干预队列。全程落在真实 MySQL 上，两张关键表：
//
//     t_tx_step          —— 补偿表：全局事务ID + 每一步的状态。
//                           补偿前先查这里，只有「已成功且没补偿过」才动手，这就是幂等，
//                           同一条补偿消息重复投递也不会退两次库存。
//     t_compensation_log —— 重试记录：每一次补偿尝试都落一条流水，事后可追查。
//
//   四个场景：三步全成功 / 扣款失败触发补偿 / 补偿重试后成功 / 补偿彻底失败转人工。
//
// 前置条件：本机 3306 有 MySQL（root/root，库名 demo）。在本目录执行 `docker compose up -d` 即可。

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    /** 补偿最多重试几次，还失败就转人工 */
    static final int MAX_RETRY = 3;

    /** 一步业务操作：返回受影响行数，0 表示这一步没做成 */
    interface Sql {
        int run(Connection conn, String txId) throws SQLException;
    }

    /** 一个业务步骤：正向做什么、反向补偿什么、补偿前几次先失败（用来演示重试） */
    record Step(String name, int order, Sql action, Sql compensate, int failTimes) {}

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            initSchema(conn);
            System.out.println("==== 演示：分布式事务补偿机制（真连 MySQL）====");

            System.out.println("\n--- 场景A：三步全成功，无需补偿 ---");
            runScenario(conn, "tx-A", 50, 0, 1, 99, 40);

            System.out.println("\n--- 场景B：余额不足扣款失败 → 触发补偿，一次成功 ---");
            runScenario(conn, "tx-B", 5, 0, 0, 100, 5);

            System.out.println("\n--- 场景C：回库存补偿前两次失败 → 重试后成功 ---");
            runScenario(conn, "tx-C", 5, 2, 0, 100, 5);

            System.out.println("\n--- 场景D：回库存补偿始终失败 → 转人工干预队列 ---");
            runScenario(conn, "tx-D", 5, 99, 0, 99, 5);

            System.out.println("\n人工干预队列（补偿表里状态为 FAILED 的步骤）："
                    + queryInt(conn, "SELECT COUNT(*) FROM t_tx_step WHERE status = 'FAILED'"));
        }
    }

    /** 建表：业务三张表 + 补偿表 + 重试记录表 */
    static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " global_tx_id VARCHAR(64), status VARCHAR(16))");
            st.execute("CREATE TABLE IF NOT EXISTS t_stock (id INT PRIMARY KEY, quantity INT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS t_account (id INT PRIMARY KEY, balance INT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS t_tx_step (global_tx_id VARCHAR(64), step_name VARCHAR(32),"
                    + " step_order INT, status VARCHAR(16), retry_count INT, fail_times INT,"
                    + " PRIMARY KEY (global_tx_id, step_name))");
            st.execute("CREATE TABLE IF NOT EXISTS t_compensation_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " global_tx_id VARCHAR(64), step_name VARCHAR(32), attempt INT, success TINYINT,"
                    + " message VARCHAR(128))");
        }
    }

    /** 跑一个场景：清干净状态 → 登记补偿表 → 正向执行 → 失败就补偿 → 对账 */
    static void runScenario(Connection conn, String txId, int balance, int stockCompensateFailTimes,
                            int expectedOrder, int expectedStock, int expectedBalance) throws SQLException {
        reset(conn, txId, balance);
        List<Step> steps = buildSteps(stockCompensateFailTimes);
        prepare(conn, txId, steps);
        try {
            execute(conn, txId, steps);
        } catch (SQLException e) {
            System.out.println("  ❌ 正向执行出错：" + e.getMessage() + " → 开始反向补偿");
            compensate(conn, txId, steps);
        }
        reconcile(conn, txId, expectedOrder, expectedStock, expectedBalance);
    }

    /** 把库存、余额清回初始值，并清掉这个事务的旧流水，方便反复运行 */
    static void reset(Connection conn, String txId, int balance) throws SQLException {
        update(conn, "DELETE FROM t_order WHERE global_tx_id = ?", txId);
        update(conn, "DELETE FROM t_tx_step WHERE global_tx_id = ?", txId);
        update(conn, "DELETE FROM t_compensation_log WHERE global_tx_id = ?", txId);
        update(conn, "INSERT INTO t_stock(id, quantity) VALUES (1, 100) ON DUPLICATE KEY UPDATE quantity = 100");
        update(conn, "INSERT INTO t_account(id, balance) VALUES (1, ?) ON DUPLICATE KEY UPDATE balance = ?",
                String.valueOf(balance), String.valueOf(balance));
    }

    /** 三个业务步骤。扣余额带 `balance >= 10` 条件，余额不够时影响 0 行，天然就是一次真实失败。 */
    static List<Step> buildSteps(int stockCompensateFailTimes) {
        Step createOrder = new Step("创建订单", 1,
                (c, tx) -> update(c, "INSERT INTO t_order(global_tx_id, status) VALUES (?, 'CREATED')", tx),
                (c, tx) -> update(c, "DELETE FROM t_order WHERE global_tx_id = ?", tx), 0);
        Step deductStock = new Step("扣库存", 2,
                (c, tx) -> update(c, "UPDATE t_stock SET quantity = quantity - 1 WHERE id = 1 AND quantity > 0"),
                (c, tx) -> update(c, "UPDATE t_stock SET quantity = quantity + 1 WHERE id = 1"),
                stockCompensateFailTimes);
        Step deductBalance = new Step("扣余额", 3,
                (c, tx) -> update(c, "UPDATE t_account SET balance = balance - 10 WHERE id = 1 AND balance >= 10"),
                (c, tx) -> update(c, "UPDATE t_account SET balance = balance + 10 WHERE id = 1"), 0);
        return List.of(createOrder, deductStock, deductBalance);
    }

    /** 记补偿表：每步先落一条 PENDING，补偿时以这张表的状态为准（幂等） */
    static void prepare(Connection conn, String txId, List<Step> steps) throws SQLException {
        for (Step step : steps) {
            update(conn, "INSERT INTO t_tx_step(global_tx_id, step_name, step_order, status, retry_count, fail_times)"
                            + " VALUES (?, ?, ?, 'PENDING', 0, ?)",
                    txId, step.name(), String.valueOf(step.order()), String.valueOf(step.failTimes()));
        }
    }

    /** 正向执行：某一步影响 0 行就抛异常，交给调用方触发补偿 */
    static void execute(Connection conn, String txId, List<Step> steps) throws SQLException {
        for (Step step : steps) {
            if (step.action().run(conn, txId) == 0) {
                throw new SQLException(step.name() + "执行失败（影响 0 行）");
            }
            markStatus(conn, txId, step.name(), "DONE");
            System.out.println("  [做] " + step.name() + " 成功（状态=DONE）");
        }
    }

    /** 反向补偿：从最后一步往前，先查补偿表状态，只有 DONE 的才需要撤销 */
    static void compensate(Connection conn, String txId, List<Step> steps) throws SQLException {
        System.out.println("  >> 开始补偿（全局事务 " + txId + "）");
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            String status = queryString(conn,
                    "SELECT status FROM t_tx_step WHERE global_tx_id = ? AND step_name = ?", txId, step.name());
            if (!"DONE".equals(status)) {
                System.out.println("  [跳过] " + step.name() + " 状态=" + status + "，无需补偿");
                continue;
            }
            compensateWithRetry(conn, txId, step);
        }
    }

    /** 补偿失败就重试，最多 3 次；每次尝试都写重试记录，全失败标记 FAILED 等人工处理 */
    static void compensateWithRetry(Connection conn, String txId, Step step) throws SQLException {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            update(conn, "UPDATE t_tx_step SET retry_count = ? WHERE global_tx_id = ? AND step_name = ?",
                    String.valueOf(attempt), txId, step.name());
            if (attempt <= step.failTimes()) {
                logAttempt(conn, txId, step.name(), attempt, false, "补偿通道不可用（模拟故障）");
                System.out.println("  [补偿] " + step.name() + " 第" + attempt + "次失败 → 发延迟消息，稍后重试");
                continue;
            }
            int affectedRows = step.compensate().run(conn, txId);
            logAttempt(conn, txId, step.name(), attempt, affectedRows > 0, "影响 " + affectedRows + " 行");
            markStatus(conn, txId, step.name(), "COMPENSATED");
            System.out.println("  [补偿] " + step.name() + " 第" + attempt + "次成功");
            return;
        }
        markStatus(conn, txId, step.name(), "FAILED");
        System.out.println("  [人工] " + step.name() + " 补偿 " + MAX_RETRY + " 次都失败，转入人工干预队列");
    }

    /** 对账：订单、库存、余额应该互相兜得住，对不上就是异常单 */
    static void reconcile(Connection conn, String txId, int expectedOrder, int expectedStock,
                          int expectedBalance) throws SQLException {
        int order = queryInt(conn, "SELECT COUNT(*) FROM t_order WHERE global_tx_id = ?", txId);
        int stock = queryInt(conn, "SELECT quantity FROM t_stock WHERE id = 1");
        int balance = queryInt(conn, "SELECT balance FROM t_account WHERE id = 1");
        boolean consistent = order == expectedOrder && stock == expectedStock && balance == expectedBalance;
        System.out.printf("  [对账] 订单=%d 库存=%d 余额=%d → %s%n",
                order, stock, balance, consistent ? "一致" : "不一致，需人工修复");
    }

    // ================= 小封装 =================

    static void markStatus(Connection conn, String txId, String stepName, String status) throws SQLException {
        update(conn, "UPDATE t_tx_step SET status = ? WHERE global_tx_id = ? AND step_name = ?",
                status, txId, stepName);
    }

    static void logAttempt(Connection conn, String txId, String stepName, int attempt, boolean success,
                           String message) throws SQLException {
        update(conn, "INSERT INTO t_compensation_log(global_tx_id, step_name, attempt, success, message)"
                        + " VALUES (?, ?, ?, ?, ?)",
                txId, stepName, String.valueOf(attempt), success ? "1" : "0", message);
    }

    static int update(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    static String queryString(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static int queryInt(Connection conn, String sql, String... params) throws SQLException {
        String value = queryString(conn, sql, params);
        return value == null ? 0 : Integer.parseInt(value);
    }
}
