// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「设计数据库表时，信息分开放用关联表连，还是顺手在表里抄一份冗余字段」。
//   我们连真实的 MySQL（JDBC 直连），建真表、写真 SQL，把两种做法各跑一遍：
//     ① 关联表（归一化）  —— `user` / `role` 各存一份，`user_role` 中间表只负责连线，
//                            查询要 JOIN；改名只改一行，查出来永远最新。
//     ② 冗余字段（反范式）—— `orders` 里把商品名、收货地址抄一份（快照），
//                            查订单不用 JOIN，一行直读；代价是源数据一变，副本就得跟着变。
//   最后演示冗余字段的一致性维护：商品改名后，用 UPDATE 把订单里的副本刷成新值。
//
// 前置条件：本机要有 MySQL。在本目录执行 `docker compose up -d` 就能起一个。
//
// 生活比喻：班主任记「谁参加了哪个社团」。关联表 = 另拿一张签到表写「小明—篮球社」，
//          学生表和社团表各管各的；冗余字段 = 直接在学生名册上加一列「社团」，抄一份进去。

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Demo {

    static final String URL = "jdbc:mysql://localhost:3306/demo"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "root")) {
            initSchema(conn);
            demoJoin(conn);
            demoSnapshot(conn);
            demoRenameAndSync(conn);
        }
    }

    // ==================================================================
    // 建表 + 灌基础数据：两张主表 + 一张中间表，再一张商品表和订单表
    // ==================================================================
    static void initSchema(Connection conn) throws Exception {
        String[] ddl = {
                "DROP TABLE IF EXISTS `user_role`",
                "DROP TABLE IF EXISTS `orders`",
                "DROP TABLE IF EXISTS `user`",
                "DROP TABLE IF EXISTS `role`",
                "DROP TABLE IF EXISTS `product`",
                "CREATE TABLE `role` (id BIGINT PRIMARY KEY, name VARCHAR(32) NOT NULL)",
                "CREATE TABLE `user` (id BIGINT PRIMARY KEY, name VARCHAR(32) NOT NULL)",
                // 中间表只存两边的 id。联合主键顺带成了「按 user_id 查角色」要用的索引
                "CREATE TABLE `user_role` (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,"
                        + " PRIMARY KEY (user_id, role_id))",
                "CREATE TABLE `product` (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)",
                // 订单表：product_id 是关联字段，后面两列是从商品/地址抄来的冗余快照
                "CREATE TABLE `orders` (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,"
                        + " product_id BIGINT NOT NULL, product_name_snapshot VARCHAR(64) NOT NULL,"
                        + " address_snapshot VARCHAR(128) NOT NULL, INDEX idx_product_id (product_id))",
        };
        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        }

        exec(conn, "INSERT INTO `role` VALUES (1, '管理员'), (2, '普通用户')");
        exec(conn, "INSERT INTO `user` VALUES (10, '张三')");
        exec(conn, "INSERT INTO `user_role` VALUES (10, 1), (10, 2)"); // 张三同时是管理员和普通用户
        exec(conn, "INSERT INTO `product` VALUES (100, '机械键盘')");
        // 下单时把商品名、地址「快照」进订单，这就是冗余字段
        exec(conn, "INSERT INTO `orders` VALUES (1001, 10, 100, '机械键盘', '北京市朝阳区xx路')");
    }

    // ==================================================================
    // 做法 A：关联表查询。角色名只存在 `role` 一处，查的时候 JOIN 连线取出来。
    // ==================================================================
    static void demoJoin(Connection conn) throws Exception {
        System.out.println("===== 做法A：关联表查询（JOIN user_role + role）=====");
        System.out.println("用户[张三] 拥有的角色：" + findRolesByUser(conn, 10L));
        explain(conn, "SELECT r.name FROM `user_role` ur JOIN `role` r ON ur.role_id = r.id"
                + " WHERE ur.user_id = 10");

        // 关联表的最大好处：改名只改 role 表一行，中间表和用户表一个字都不用动，
        // 下次查询 JOIN 出来自动就是新名字 —— 不存在「两份数据对不上」的可能。
        exec(conn, "UPDATE `role` SET name = '超级管理员' WHERE id = 1");
        System.out.println("把角色 1 改名为「超级管理员」后重新查：" + findRolesByUser(conn, 10L));
        System.out.println();
    }

    // ==================================================================
    // 做法 B：冗余字段查询。商品名已经抄在订单行里，查订单不碰商品表，一行直读。
    // ==================================================================
    static void demoSnapshot(Connection conn) throws Exception {
        System.out.println("===== 做法B：冗余字段查询（直接读订单里的快照）=====");
        System.out.println("订单 1001：" + readOrderSnapshot(conn, 1001L));
        explain(conn, "SELECT product_name_snapshot, address_snapshot FROM `orders` WHERE id = 1001");
        System.out.println();
    }

    // ==================================================================
    // 冗余字段的代价：源数据一变，所有副本都得跟着变，否则就是脏数据。
    // 生产里这步由 Canal/binlog 监听或 MQ 消费者触发，这里直接跑同步 SQL。
    // ==================================================================
    static void demoRenameAndSync(Connection conn) throws Exception {
        System.out.println("===== 商品改名：源数据变了 =====");
        exec(conn, "UPDATE `product` SET name = '客制化机械键盘' WHERE id = 100");
        System.out.println("product 表改名后，订单里的快照还是旧的：" + readOrderSnapshot(conn, 1001L));

        int updatedRows;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE `orders` SET product_name_snapshot = ? WHERE product_id = ?")) {
            ps.setString(1, "客制化机械键盘");
            ps.setLong(2, 100L);
            updatedRows = ps.executeUpdate();
        }
        System.out.println("同步 SQL 刷了 " + updatedRows + " 条订单 → " + readOrderSnapshot(conn, 1001L));
        System.out.println("→ 冗余字段读得快，但必须有一条「源一变、副本跟着变」的同步链路兜住一致性。");
    }

    // ==================================================================
    // 下面是小工具
    // ==================================================================

    /** 关联表查询：等价于 SELECT r.name FROM user_role ur JOIN role r ON ur.role_id = r.id。 */
    static List<String> findRolesByUser(Connection conn, long userId) throws Exception {
        String sql = "SELECT r.name FROM `user_role` ur JOIN `role` r ON ur.role_id = r.id"
                + " WHERE ur.user_id = ? ORDER BY r.id";
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
    }

    /** 读订单里的冗余快照（不 JOIN 任何表）。 */
    static String readOrderSnapshot(Connection conn, long orderId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT product_name_snapshot, address_snapshot FROM `orders` WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? "商品名=" + rs.getString(1) + "，收货地址=" + rs.getString(2)
                        : "订单不存在";
            }
        }
    }

    /** 打印 EXPLAIN 的关键几列：走的什么访问方式、用了哪个索引、预计扫多少行。 */
    static void explain(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                System.out.println("  EXPLAIN → table=" + rs.getString(3)
                        + ", type=" + rs.getString(5)
                        + ", key=" + rs.getString(7)
                        + ", rows=" + rs.getString(10)
                        + ", Extra=" + rs.getString(12));
            }
        }
    }

    /** 执行一条不需要参数的 SQL（建表、灌数据、改数据）。 */
    static void exec(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
