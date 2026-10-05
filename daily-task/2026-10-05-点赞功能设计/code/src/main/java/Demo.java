/*
 * 朋友圈点赞功能 Demo
 *
 * 演示 4 个核心动作（对应原题 5 点）：
 *   场景1 普通动态点赞：Redis Set 原子去重（SADD）+ 计数器自增（INCR）+ 落库
 *   场景2 热点动态：点赞事件先进 MQ 削峰，消费者按批落库，把 200 次写库压成 4 批
 *   场景3 读点赞数：先读 Redis 缓存（O(1)），miss 才回源 MySQL 并回填
 *   场景4 展示点赞的人：只给总数 + 前几个，不全量拉列表
 *
 * 前置条件（先起中间件，端口已避开用户自有服务）：
 *   cd code && docker compose up -d
 *   等待 redis:6380 / mysql:3307 / rabbitmq:5673 就绪，再执行 mvn exec:java
 */
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Demo {

    static final String REDIS_HOST = "127.0.0.1";
    static final int REDIS_PORT = 6380;
    static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3307/like_demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String RABBIT_HOST = "127.0.0.1";
    static final int RABBIT_PORT = 5673;
    static final String LIKE_QUEUE = "feed.like.events";

    static JedisPool jedisPool;
    static Connection db;
    static com.rabbitmq.client.Connection mqConnection;
    static Channel mqChannel;

    public static void main(String[] args) throws Exception {
        connectAll();
        // 必须 finally 关闭：MQ 客户端的连接线程不是守护线程，
        // 一旦中途抛异常没断开连接，进程就退不出去（exec:java 会一直等）
        try {
            createTable();
            resetData();

            step1NormalLike(1001L);
            step2HotFeedByMq(2002L, 200);
            step3ReadLikeCount(1001L);
            step4ShowLikeList(1001L);
        } finally {
            closeAll();
        }
    }

    // ---------- 连接与准备 ----------

    static void connectAll() throws Exception {
        jedisPool = new JedisPool(REDIS_HOST, REDIS_PORT);
        db = DriverManager.getConnection(MYSQL_URL, "root", "root");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setPort(RABBIT_PORT);
        factory.setUsername("guest");
        factory.setPassword("guest");
        mqConnection = factory.newConnection();
        mqChannel = mqConnection.createChannel();
        mqChannel.queueDeclare(LIKE_QUEUE, false, false, false, null);
    }

    static void createTable() throws SQLException {
        // 唯一索引 (feed_id, user_id) 是数据库层的最后一道防重闸门：
        // 就算 Redis 判重漏了、消息重复投递了，同一个人也不可能写出两条点赞记录
        try (Statement st = db.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS feed_like (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      feed_id BIGINT NOT NULL,
                      user_id BIGINT NOT NULL,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      UNIQUE KEY uk_feed_user (feed_id, user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    static void resetData() throws Exception {
        try (Statement st = db.createStatement()) {
            st.execute("TRUNCATE TABLE feed_like");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            // 演示环境才敢用 keys，生产环境要用 scan
            var keys = jedis.keys("like*");
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
        mqChannel.queuePurge(LIKE_QUEUE); // 清掉上一轮可能残留的点赞事件
    }

    // ---------- 场景1：普通动态点赞 ----------

    static void step1NormalLike(long feedId) throws Exception {
        System.out.println("== 场景1：普通动态点赞（Redis 原子去重 + 计数 + 落库）==");
        try (Jedis jedis = jedisPool.getResource()) {
            for (long userId = 1; userId <= 5; userId++) {
                likeOnce(jedis, feedId, userId);
            }
            // 1 号用户手滑又点了一次：SADD 返回 0，说明他已经在集合里，计数不能再加
            likeOnce(jedis, feedId, 1L);
            System.out.println("  feed=" + feedId + " Redis 计数器 = " + jedis.get(countKey(feedId)));
        }
        System.out.println("  MySQL 里实际记录数 = " + countInDb(feedId));
    }

    /** 一次点赞：先问 Redis「这个人点过没」，没点过才加计数、才写库。 */
    static void likeOnce(Jedis jedis, long feedId, long userId) throws SQLException {
        long added = jedis.sadd(likesKey(feedId), String.valueOf(userId));
        if (added == 0) {
            System.out.println("  用户 " + userId + " 重复点赞，直接忽略");
            return;
        }
        long count = jedis.incr(countKey(feedId));
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT IGNORE INTO feed_like(feed_id, user_id) VALUES (?, ?)")) {
            ps.setLong(1, feedId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
        System.out.println("  用户 " + userId + " 点赞成功，当前点赞数 = " + count);
    }

    // ---------- 场景2：热点动态走 MQ 削峰 + 批量落库 ----------

    static void step2HotFeedByMq(long feedId, int userCount) throws Exception {
        System.out.println("== 场景2：热点动态，点赞事件进 MQ 削峰，消费者批量落库 ==");
        try (Jedis jedis = jedisPool.getResource()) {
            for (long userId = 1; userId <= userCount; userId++) {
                // 点赞结果先写 Redis（用户立刻看到已点赞），再把事件丢进队列慢慢落库
                if (jedis.sadd(likesKey(feedId), String.valueOf(userId)) == 1) {
                    jedis.incr(countKey(feedId));
                    mqChannel.basicPublish("", LIKE_QUEUE, null,
                            (feedId + "," + userId).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        System.out.println("  瞬时涌入 " + userCount + " 次点赞，事件全部进队列，用户侧已即时生效");

        int batchSize = 50;
        int batches = consumeAndSaveInBatch(batchSize);
        System.out.println("  消费者按 " + batchSize + " 条/批落库，共 " + batches + " 批（200 次写库被压成 "
                + batches + " 批）");
        System.out.println("  MySQL 里 feed=" + feedId + " 的记录数 = " + countInDb(feedId));
    }

    /** 把队列里的消息一批批取出来，凑够一批就落库、落库成功才 ack。 */
    static int consumeAndSaveInBatch(int batchSize) throws Exception {
        int batches = 0;
        List<long[]> batch = new ArrayList<>(batchSize);
        List<Long> tags = new ArrayList<>(batchSize);

        GetResponse message;
        while ((message = mqChannel.basicGet(LIKE_QUEUE, false)) != null) {
            String[] parts = new String(message.getBody(), StandardCharsets.UTF_8).split(",");
            batch.add(new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])});
            tags.add(message.getEnvelope().getDeliveryTag());
            if (batch.size() == batchSize) {
                saveAndAck(batch, tags);
                batches++;
            }
        }
        if (!batch.isEmpty()) {
            saveAndAck(batch, tags);
            batches++;
        }
        return batches;
    }

    static void saveAndAck(List<long[]> batch, List<Long> tags) throws Exception {
        saveBatch(batch);
        for (long tag : tags) {
            mqChannel.basicAck(tag, false); // 落库成功才确认，消息不会凭空丢
        }
        batch.clear();
        tags.clear();
    }

    static void saveBatch(List<long[]> rows) throws SQLException {
        // 一条 INSERT 带多组 VALUES：一次网络往返写一批，比逐条插快一个数量级
        String sql = "INSERT IGNORE INTO feed_like(feed_id, user_id) VALUES "
                + String.join(", ", Collections.nCopies(rows.size(), "(?, ?)"));
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            int index = 1;
            for (long[] row : rows) {
                ps.setLong(index++, row[0]);
                ps.setLong(index++, row[1]);
            }
            ps.executeUpdate();
        }
    }

    // ---------- 场景3：读点赞数，缓存优先 ----------

    static void step3ReadLikeCount(long feedId) throws SQLException {
        System.out.println("== 场景3：读点赞数，先走缓存，miss 才回源 MySQL ==");
        try (Jedis jedis = jedisPool.getResource()) {
            System.out.println("  第 1 次读（缓存命中，O(1)）        -> " + readCount(jedis, feedId));

            jedis.del(countKey(feedId)); // 模拟缓存失效
            System.out.println("  缓存被清掉后读（回源 MySQL 并回填）-> " + readCount(jedis, feedId));
            System.out.println("  第 3 次读（缓存已回填，不再查库）  -> " + readCount(jedis, feedId));
        }
    }

    static long readCount(Jedis jedis, long feedId) throws SQLException {
        String cached = jedis.get(countKey(feedId));
        if (cached != null) {
            return Long.parseLong(cached);
        }
        long fromDb = countInDb(feedId);
        jedis.setex(countKey(feedId), 300, String.valueOf(fromDb)); // 5 分钟后过期，防止缓存长期跑偏
        return fromDb;
    }

    // ---------- 场景4：展示点赞列表 ----------

    static void step4ShowLikeList(long feedId) {
        System.out.println("== 场景4：展示点赞的人，只给总数 + 前几个，不全量拉 ==");
        try (Jedis jedis = jedisPool.getResource()) {
            long total = jedis.scard(likesKey(feedId));
            List<String> preview = jedis.smembers(likesKey(feedId)).stream()
                    .sorted()
                    .limit(3)
                    .toList();
            System.out.println("  feed=" + feedId + " 共 " + total + " 人点赞，页面只渲染前 3 个：" + preview);
            System.out.println("  想看全部再翻页查 MySQL，避免一次把几十万人拉进内存");
        }
    }

    // ---------- 工具方法 ----------

    static String likesKey(long feedId) {
        return "likes:" + feedId; // Redis Set：存点过赞的用户 ID，天然去重
    }

    static String countKey(long feedId) {
        return "like_count:" + feedId; // Redis 计数器：查一条动态的点赞数是 O(1)
    }

    static long countInDb(long feedId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM feed_like WHERE feed_id = ?")) {
            ps.setLong(1, feedId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void closeAll() throws Exception {
        mqChannel.close();
        mqConnection.close();
        db.close();
        jedisPool.close();
    }
}
