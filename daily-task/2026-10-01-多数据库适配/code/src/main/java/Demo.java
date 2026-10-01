/*
 * 这个程序在干嘛
 * ------------------------------------------------------------------
 * 演示「同一个功能，怎么同时适配 MySQL 和 Oracle」。
 * 核心动作一句话：把会变的东西（各家数据库的 SQL 方言）关进小盒子里，
 * 业务代码只认盒子上的接口，不认盒子里的实现。
 *
 * 四步演示：
 *   1. 建数据 —— 两种数据库各建一张结构一样的表，SQL 全是标准语法，所以两边都能跑。
 *   2. 方言隔离 —— 同一段 ProductDao.findPage()，分别装上 MySQL 方言（LIMIT/OFFSET）
 *      和 Oracle 方言（ROWNUM 两层嵌套），各查第 4~8 行，结果必须一致。
 *   3. 空值兜底 —— MySQL 用 IFNULL、Oracle 用 NVL，同一个业务需求两种写法。
 *   4. 驱动可插拔 —— 换数据库只换 JDBC URL，Java 代码一个字都不用改。
 *
 * 这里刻意用了两种真实数据库引擎：MySQL 8.4 跑在容器里；Oracle 因为镜像太重，
 * 用 H2 的 Oracle 兼容模式（MODE=Oracle）代替，它同样支持 ROWNUM / NVL / DUAL，
 * 用来验证 Oracle 方言的 SQL 确实能真实执行。
 *
 * 前置条件：
 *   cd code && docker compose up -d        # 起 MySQL 8.4，映射到宿主机 3307
 *   （H2 是纯 Java 内存库，由 JDBC 驱动直接拉起，不需要容器）
 *   然后执行：mvn -q exec:java
 */

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 分页查询的产物：SQL 文本（取值处留 ? 占位） + 按顺序要绑定的参数 */
record PageQuery(String sql, List<Object> params) {}

/** 方言接口：一种数据库一个实现，业务层只认这个接口（就是原题说的 SqlBuilder） */
interface SqlBuilder {

    String name();

    PageQuery buildPageQuery(String table, String orderBy, int offset, int limit);

    String buildIfNull(String column, String fallback);
}

/** MySQL 方言：分页用 LIMIT ? OFFSET ?，空值兜底用 IFNULL */
class MySqlSqlBuilder implements SqlBuilder {

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public PageQuery buildPageQuery(String table, String orderBy, int offset, int limit) {
        String sql = "SELECT id, name FROM " + table + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?";
        return new PageQuery(sql, List.of(limit, offset));
    }

    @Override
    public String buildIfNull(String column, String fallback) {
        return "IFNULL(" + column + ", '" + fallback + "')";
    }
}

/** Oracle 方言：老版本 Oracle 没有 LIMIT，只能靠 ROWNUM 套两层子查询来分页 */
class OracleSqlBuilder implements SqlBuilder {

    @Override
    public String name() {
        return "Oracle";
    }

    @Override
    public PageQuery buildPageQuery(String table, String orderBy, int offset, int limit) {
        String sql = "SELECT * FROM (SELECT t.*, ROWNUM rn FROM "
                + "(SELECT id, name FROM " + table + " ORDER BY " + orderBy + ") t"
                + " WHERE ROWNUM <= ?) WHERE rn > ?";
        // 先取到 offset+limit 行，再把这之前的 offset 行丢掉 —— ROWNUM 只能「小于等于」，不能直接跳过
        return new PageQuery(sql, List.of(offset + limit, offset));
    }

    @Override
    public String buildIfNull(String column, String fallback) {
        return "NVL(" + column + ", '" + fallback + "')";
    }
}

/** 方言工厂：运行时按配置项挑实现，业务代码拿到的永远是 SqlBuilder 接口 */
class SqlBuilderFactory {

    static SqlBuilder of(String dbType) {
        return switch (dbType.toLowerCase()) {
            case "mysql" -> new MySqlSqlBuilder();
            case "oracle" -> new OracleSqlBuilder();
            default -> throw new IllegalArgumentException("暂不支持的数据库类型: " + dbType);
        };
    }
}

/** 业务层：只依赖 SqlBuilder 接口，通篇看不到 LIMIT / ROWNUM 这些方言词 */
class ProductDao {

    private final SqlBuilder sqlBuilder;

    ProductDao(SqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    /** 查一页商品。以后新增一种数据库，这个方法一个字都不用改 */
    List<String> findPage(Connection conn, int offset, int limit) throws SQLException {
        PageQuery query = sqlBuilder.buildPageQuery("product", "id", offset, limit);
        System.out.println("  [" + sqlBuilder.name() + "] SQL = " + query.sql());

        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(query.sql())) {
            for (int i = 0; i < query.params().size(); i++) {
                ps.setObject(i + 1, query.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getInt("id") + ":" + rs.getString("name"));
                }
            }
        }
        return rows;
    }
}

public class Demo {

    private static final int OFFSET = 3;
    private static final int LIMIT = 5;

    // 换数据库就换这一行 URL —— 这就是「驱动可插拔」的全部代价
    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3307/demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    private static final String ORACLE_LIKE_URL = "jdbc:h2:mem:oracleLike;MODE=Oracle;DB_CLOSE_DELAY=-1";

    public static void main(String[] args) throws Exception {
        try (Connection mysqlConn = DriverManager.getConnection(MYSQL_URL, "root", "root");
             Connection oracleConn = DriverManager.getConnection(ORACLE_LIKE_URL, "sa", "")) {

            System.out.println("== 第 1 步：两种数据库各建一张结构一样的表 ==");
            prepareData(mysqlConn);
            prepareData(oracleConn);
            System.out.println("  两边都建好了 product 表：12 行正常数据 + 1 行 name 为 NULL 的数据");

            System.out.println("\n== 第 2 步：同一段业务代码，配不同方言，各查第 4~8 行 ==");
            // 真实项目里这里读的是配置项 db.type，不是写死的字符串
            List<String> mysqlPage = new ProductDao(SqlBuilderFactory.of("mysql")).findPage(mysqlConn, OFFSET, LIMIT);
            List<String> oraclePage = new ProductDao(SqlBuilderFactory.of("oracle")).findPage(oracleConn, OFFSET, LIMIT);
            System.out.println("  MySQL  查出 : " + mysqlPage);
            System.out.println("  Oracle 查出 : " + oraclePage);
            System.out.println("  两边结果一致？ " + mysqlPage.equals(oraclePage));

            System.out.println("\n== 第 3 步：空值兜底函数也不一样（IFNULL / NVL）==");
            System.out.println("  MySQL  兜底结果: " + queryNullName(mysqlConn, SqlBuilderFactory.of("mysql")));
            System.out.println("  Oracle 兜底结果: " + queryNullName(oracleConn, SqlBuilderFactory.of("oracle")));

            System.out.println("\n== 第 4 步：驱动可插拔，JDBC 自己就认得连的是谁 ==");
            System.out.println("  MySQL  连接认到的是 : " + productName(mysqlConn));
            System.out.println("  Oracle 连接认到的是 : " + productName(oracleConn));
        }
    }

    /** 建表 + 塞 12 行数据 + 1 行 name 为 NULL 的数据。SQL 全是标准语法，所以两边都能跑 */
    private static void prepareData(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS product");
            st.execute("CREATE TABLE product (id INT PRIMARY KEY, name VARCHAR(32))");
            for (int id = 1; id <= 12; id++) {
                st.execute("INSERT INTO product (id, name) VALUES (" + id + ", '商品" + id + "')");
            }
            st.execute("INSERT INTO product (id, name) VALUES (99, NULL)");
        }
    }

    /** 用方言拼出的空值函数去查 id=99 那一行（它的 name 是 NULL） */
    private static String queryNullName(Connection conn, SqlBuilder sqlBuilder) throws SQLException {
        String sql = "SELECT " + sqlBuilder.buildIfNull("name", "无名") + " shown FROM product WHERE id = 99";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString("shown") : "(无数据)";
        }
    }

    private static String productName(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
    }
}
