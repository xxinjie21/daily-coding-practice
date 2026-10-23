import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 演示原题：一笔订单被微信、支付宝同时支付，怎么保证只成功一次。
 *
 * 严格按原题给的三招实现，逐个对上号：
 *   ① 先查状态/缓存，已支付直接返回成功 —— Redis 缓存支付结果（pay:result:&lt;订单号&gt;）
 *   ② 状态机条件更新 —— UPDATE ... WHERE order_no=? AND status='WAIT_PAY'，影响 0 行即已被处理
 *   ③ 唯一索引兜底 —— t_pay_record 建 UNIQUE(order_no, channel)，同渠道重复插会被 MySQL 拒（1062）
 *
 * 前置条件：先 docker compose up -d 起 MySQL(3307, root/root, 库 demo) 和 Redis(6380)，再 mvn exec:java。
 */
public class Demo {

    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3307/demo";
    static final String DB_USER = "root";
    static final String DB_PASSWORD = "root";
    /** 支付结果缓存 key 前缀，对应原题「Redis 可以缓存订单支付结果」 */
    static final String RESULT_KEY_PREFIX = "pay:result:";

    static final JedisPool REDIS_POOL = new JedisPool("127.0.0.1", 6380);
    /** 记录本局谁抢到了状态更新，供后面的「回调重发」演示使用 */
    static volatile String winner;

    public static void main(String[] args) throws Exception {
        initTables();
        System.out.println("==== 场景一：不设防，微信/支付宝同时回调（反面教材） ====");
        race("O1001", false);

        System.out.println();
        System.out.println("==== 场景二：三道防线，微信/支付宝同时回调 ====");
        race("O1002", true);
        System.out.println("胜出渠道：" + winner);

        System.out.println();
        System.out.println("==== 场景三：回调重发/重试，防线还拦得住吗 ====");
        retryScenarios("O1002");

        System.out.println();
        System.out.println("==== 对账：两笔订单最终各剩几条流水 ====");
        printSummary();
        REDIS_POOL.close();
    }

    /** 两个渠道用「发令枪」保证同时发起回调，模拟真实并发 */
    static void race(String orderNo, boolean guard) throws InterruptedException {
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (String channel : new String[]{"WX", "ALI"}) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    System.out.println(channel + " 回调结果：" + payCallback(orderNo, channel, guard));
                } catch (Exception e) {
                    System.out.println(channel + " 回调异常：" + e.getMessage());
                }
            });
        }
        startGun.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * 一条支付回调的处理流程。guard=false 时完全不设防（来一条记一条）。
     */
    static String payCallback(String orderNo, String channel, boolean guard) throws SQLException {
        if (!guard) {
            // 反面教材：不查状态直接记流水 + 无条件改状态，两个渠道都会认为成功
            update("INSERT INTO t_pay_record(order_no, channel, amount) VALUES(?,?,?)", orderNo, channel, 100);
            update("UPDATE t_order SET status='PAID' WHERE order_no=?", orderNo);
            return "支付成功（不设防）";
        }
        // ① 前置缓存：这单已经付过，直接返回成功，连数据库都不碰
        try (Jedis jedis = REDIS_POOL.getResource()) {
            if (jedis.get(RESULT_KEY_PREFIX + orderNo) != null) {
                return "已支付，直接返回成功（缓存命中）";
            }
        }
        // ② 状态机条件更新：只有「待支付」能改成「已支付」，影响 0 行说明被别的请求抢先了
        int updated = update("UPDATE t_order SET status='PAID' WHERE order_no=? AND status='WAIT_PAY'", orderNo);
        if (updated == 0) {
            return "订单已支付，拒绝本次回调（状态机拦截）";
        }
        // ③ 抢到更新的赢家才落流水，并写缓存给后面的回调挡枪
        update("INSERT INTO t_pay_record(order_no, channel, amount) VALUES(?,?,?)", orderNo, channel, 100);
        try (Jedis jedis = REDIS_POOL.getResource()) {
            jedis.setex(RESULT_KEY_PREFIX + orderNo, 600, "PAID");
        }
        winner = channel;
        return "支付成功（抢到状态更新，落流水 + 写缓存）";
    }

    /** 场景三：支付成功的那个渠道又推了一次回调（网络重试 / MQ 重投都会这样） */
    static void retryScenarios(String orderNo) throws SQLException {
        System.out.println(winner + " 回调重发（缓存还在）：" + payCallback(orderNo, winner, true));

        // 模拟 Redis 缓存失效（重启、抖动、被淘汰），第一道门没了
        try (Jedis jedis = REDIS_POOL.getResource()) {
            jedis.del(RESULT_KEY_PREFIX + orderNo);
        }
        System.out.println(winner + " 再次重发（缓存已删）：" + payCallback(orderNo, winner, true));

        // 兜底中的兜底：假设有路径绕过状态检查直接补流水，唯一索引照样拒之门外
        try {
            update("INSERT INTO t_pay_record(order_no, channel, amount) VALUES(?,?,?)", orderNo, winner, 100);
            System.out.println("居然插进去了，不可能");
        } catch (SQLException e) {
            // MySQL 重复键错误：errorCode 1062 / SQLState 23000
            System.out.println("唯一索引拦截重复流水：errorCode=" + e.getErrorCode() + "，按已处理返回");
        }
        // 被拒恰恰说明订单确实已支付，顺手把缓存补回去，后面的回调又能走快速通道
        try (Jedis jedis = REDIS_POOL.getResource()) {
            jedis.setex(RESULT_KEY_PREFIX + orderNo, 600, "PAID");
        }
    }

    /** 建表 + 造两笔待支付订单；uk_order_channel 就是原题说的「订单ID + 渠道」唯一索引 */
    static void initTables() throws SQLException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_pay_record");
            st.execute("DROP TABLE IF EXISTS t_order");
            st.execute("""
                CREATE TABLE t_order (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_no VARCHAR(32) NOT NULL UNIQUE,
                  amount DECIMAL(10,2) NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )""");
            st.execute("""
                CREATE TABLE t_pay_record (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_no VARCHAR(32) NOT NULL,
                  channel VARCHAR(16) NOT NULL,
                  amount DECIMAL(10,2) NOT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_order_channel (order_no, channel)
                )""");
            st.execute("INSERT INTO t_order(order_no, amount, status) VALUES('O1001', 100.00, 'WAIT_PAY')");
            st.execute("INSERT INTO t_order(order_no, amount, status) VALUES('O1002', 100.00, 'WAIT_PAY')");
        }
        // 清掉上次运行的缓存，保证每次演示从零开始
        try (Jedis jedis = REDIS_POOL.getResource()) {
            jedis.del(RESULT_KEY_PREFIX + "O1001", RESULT_KEY_PREFIX + "O1002");
        }
    }

    /** 执行更新类 SQL，返回受影响行数；params 按顺序绑定占位符 */
    static int update(String sql, Object... params) throws SQLException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    /** 对账：订单状态 + 支付流水条数 + Redis 缓存值 */
    static void printSummary() throws SQLException {
        String sql = "SELECT o.status, (SELECT COUNT(*) FROM t_pay_record p WHERE p.order_no = o.order_no) "
                + "FROM t_order o WHERE o.order_no = ?";
        for (String orderNo : new String[]{"O1001", "O1002"}) {
            try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderNo);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    System.out.println(orderNo + "：状态=" + rs.getString(1) + "，支付流水=" + rs.getInt(2) + " 条");
                }
            }
        }
        try (Jedis jedis = REDIS_POOL.getResource()) {
            System.out.println("Redis 缓存 pay:result:O1002 = " + jedis.get(RESULT_KEY_PREFIX + "O1002"));
        }
    }
}
