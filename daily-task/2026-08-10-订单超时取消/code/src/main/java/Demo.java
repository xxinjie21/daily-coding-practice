/*
 * 订单超时取消：30 分钟不付款，怎么把单准点关掉
 * ----------------------------------------------------------------------------
 * 用真实的 Redis（Jedis） + Kafka 演示原题里「异步化 + 延迟消息」这条主线：
 *
 *   实验一：下单时把「到期时刻」写进 Redis ZSET，score 就是到期时间戳。
 *           ZRANGEBYSCORE 从最小的一端往后取，只摸到期的几条，不用每分钟 select 全表。
 *   实验二：取到期任务必须用 Lua 脚本，把「查 + 删」打包成一次不可打断的操作，
 *           否则两个 worker 会抢到同一单，同一张单被关两次。
 *   实验三：调度器把到期订单投到 Kafka 的 order-timeout topic，关单消费者异步消费。
 *           消息落在磁盘上，服务重启、发版、崩溃，信都还在。
 *   实验四：Kafka 会重投，所以关单必须幂等 —— 用 SET NX 去重标记保证同一条只处理一次。
 *
 * 前置条件：本机 Redis 7 + Kafka 3.7（见同目录 docker-compose.yml），均为默认端口无密码。
 */

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

public class Demo {

    /** 延迟队列：ZSET 的 score 存订单的「到期时刻」时间戳。 */
    static final String TIMEOUT_ZSET = "orders:timeout";
    /** 关单任务投递的 Kafka topic。 */
    static final String CLOSE_TOPIC = "order-timeout";

    /**
     * 原子地取出最早到期的订单：ZRANGEBYSCORE 取一条 + ZREM 删掉，一次脚本执行完。
     * Redis 执行 Lua 时是单线程、不可打断的，所以不会有第二个 worker 拿到同一条。
     */
    static final String POP_DUE_LUA = """
            local due = redis.call('zrangebyscore', KEYS[1], 0, ARGV[1], 'LIMIT', 0, 1)
            if #due == 0 then return nil end
            redis.call('zrem', KEYS[1], due[1])
            return due[1]
            """;

    public static void main(String[] args) throws Exception {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.del(TIMEOUT_ZSET, "queue:a", "queue:b");
            experiment1_delayQueueByZset(jedis);
            experiment2_atomicPopNeedsLua(jedis);
            experiment3_kafkaDelivery(jedis);
            experiment4_idempotentClose(jedis);
        }
        System.out.println("\n=== 一句话提醒 ===");
        System.out.println("关单和支付永远在赛跑，代码里绝不能出现「先 select 判断、再 update 修改」两条分开的语句：");
        System.out.println("把条件写进 update orders set status = 2 where id = ? and status = 0，让数据库替你判断。");
    }

    // 实验一：ZSET 就是那条「按到期时刻排好队」的延迟队列
    static void experiment1_delayQueueByZset(Jedis jedis) {
        title("实验一：下单时把「30 分钟后到期」写进 Redis ZSET");
        long now = System.currentTimeMillis();
        jedis.zadd(TIMEOUT_ZSET, now - 1000, "ORD-DUE-1");        // 已经到点
        jedis.zadd(TIMEOUT_ZSET, now + 60_000, "ORD-FUTURE-1");   // 还有 1 分钟
        jedis.zadd(TIMEOUT_ZSET, now + 120_000, "ORD-FUTURE-2");  // 还有 2 分钟

        List<String> due = jedis.zrangeByScore(TIMEOUT_ZSET, 0, now);
        List<String> all = jedis.zrange(TIMEOUT_ZSET, 0, -1);
        System.out.println("队列里一共 " + all.size() + " 单，其中已到期的 " + due.size() + " 单：" + due);
        System.out.println("ZSET 天生按分数排好序，只需要从最小的一端往后取，碰几条就是几条 ——");
        System.out.println("对比「每分钟 select 一遍订单表」，为了 5 条超时单要翻 20 万行，差别就在这。");
        System.out.println("代价：Redis 挂了这批待关单的任务就没了，生产上要配一个「每小时扫一次库」的兜底补偿任务。");
    }

    // 实验二：查 + 删必须是原子的
    static void experiment2_atomicPopNeedsLua(Jedis jedis) throws InterruptedException {
        title("实验二：取到期任务为什么必须用 Lua —— 先查后删会被两个 worker 抢到同一单");
        long now = System.currentTimeMillis();

        fillDueOrders(jedis, "queue:a", now);
        Map<String, AtomicInteger> twoStepGrab = new ConcurrentHashMap<>();
        runTwoWorkers(() -> {
            try (Jedis workerJedis = new Jedis("localhost", 6379)) {
                while (true) {
                    List<String> due = workerJedis.zrangeByScore("queue:a", 0, System.currentTimeMillis());
                    if (due.isEmpty()) {
                        return;
                    }
                    String orderId = due.get(0);
                    Thread.yield();                     // 就是这条缝，让另一个线程插了进来
                    workerJedis.zrem("queue:a", orderId);
                    twoStepGrab.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
                }
            }
        });
        System.out.println("A. zrangebyscore 查完再 zrem 删（两步）: 200 条到期订单，被抢到两次的有 "
                + countDuplicated(twoStepGrab) + " 条");

        fillDueOrders(jedis, "queue:b", now);
        Map<String, AtomicInteger> luaGrab = new ConcurrentHashMap<>();
        runTwoWorkers(() -> {
            try (Jedis workerJedis = new Jedis("localhost", 6379)) {
                while (true) {
                    String orderId = asString(workerJedis.eval(POP_DUE_LUA, List.of("queue:b"),
                            List.of(String.valueOf(System.currentTimeMillis()))));
                    if (orderId == null) {
                        return;
                    }
                    luaGrab.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
                }
            }
        });
        System.out.println("B. Lua 脚本一次搞定（原子）        : 200 条到期订单，被抢到两次的有 "
                + countDuplicated(luaGrab) + " 条");
        System.out.println("被抢两次的订单就是会被关两次的订单 —— 关单还要发短信、退优惠券、还库存，重复一次就是一次客诉。");
    }

    // 实验三：到期任务投给 Kafka，关单消费者异步处理
    static void experiment3_kafkaDelivery(Jedis jedis) {
        title("实验三：把到期订单投给 Kafka，让关单消费者异步处理");
        long now = System.currentTimeMillis();
        jedis.zadd(TIMEOUT_ZSET, now - 2000, "ORD-1001");
        jedis.zadd(TIMEOUT_ZSET, now - 1000, "ORD-1002");

        List<String> dueOrders = new ArrayList<>();
        try (Jedis schedulerJedis = new Jedis("localhost", 6379);
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            while (true) {
                String orderId = asString(schedulerJedis.eval(POP_DUE_LUA, List.of(TIMEOUT_ZSET),
                        List.of(String.valueOf(System.currentTimeMillis()))));
                if (orderId == null) {
                    break;
                }
                dueOrders.add(orderId);
                producer.send(new ProducerRecord<>(CLOSE_TOPIC, orderId, orderId + "@" + now));
            }
            producer.flush();
        }
        System.out.println("调度器把 " + dueOrders.size() + " 条到期订单投到 Kafka topic「" + CLOSE_TOPIC + "」：" + dueOrders);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(List.of(CLOSE_TOPIC));
            long deadline = System.currentTimeMillis() + 5000;
            int consumed = 0;
            while (consumed < dueOrders.size() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    consumed++;
                    System.out.println("  消费者收到 key=" + record.key() + " value=" + record.value()
                            + " partition=" + record.partition() + " offset=" + record.offset());
                }
            }
        }
        System.out.println("--> 下单时只是「预约了一个 30 分钟后的闹钟」，时间到了中间件主动来叫我，不用每分钟去问数据库。");
    }

    // 实验四：Kafka 会重投，关单必须幂等
    static void experiment4_idempotentClose(Jedis jedis) {
        title("实验四：Kafka 会重投，关单必须幂等");
        String orderId = "ORD-1001";
        String dedupKey = "order:closed:" + orderId;
        jedis.del(dedupKey);

        int closedTimes = 0;
        for (int delivery = 1; delivery <= 3; delivery++) {
            // SET order:closed:<id> 1 NX EX 600：只有第一次能写进去，后面几次返回 null
            String firstWrite = jedis.set(dedupKey, "1", SetParams.setParams().nx().ex(600));
            if ("OK".equals(firstWrite)) {
                closedTimes++;
                System.out.println("  第 " + delivery + " 次投递：抢到去重标记 -> 真正关单 + 还库存 + 发短信");
            } else {
                System.out.println("  第 " + delivery + " 次投递：标记已存在 -> 别人处理过了，直接 ack 丢弃");
            }
        }
        System.out.println("--> 同一条消息投 3 次只关单 " + closedTimes + " 次。");
        System.out.println("    Redis 标记会过期，真正的兜底是让数据库替你判断状态：");
        System.out.println("    update orders set status = 2 where id = ? and status = 0，返回受影响行数 1 才算真关上。");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 造 200 条已经到期的订单，分数都小于当前时间。 */
    static void fillDueOrders(Jedis jedis, String queueKey, long now) {
        jedis.del(queueKey);
        for (int i = 0; i < 200; i++) {
            jedis.zadd(queueKey, now - 100 + (i % 50), "ORD-DUE-" + i);
        }
    }

    /** 两个 worker 同时抢任务，用来暴露「先查后删」那条缝。 */
    static void runTwoWorkers(Runnable job) throws InterruptedException {
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    startGun.await();
                    job.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            }).start();
        }
        startGun.countDown();
        allDone.await();
    }

    static int countDuplicated(Map<String, AtomicInteger> grabCount) {
        int duplicated = 0;
        for (AtomicInteger times : grabCount.values()) {
            if (times.get() > 1) {
                duplicated++;
            }
        }
        return duplicated;
    }

    /** Lua 返回的字符串在 Jedis 里可能是 String 也可能是 byte[]，统一转成 String。 */
    static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    static Properties producerProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        return props;
    }

    static Properties consumerProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("group.id", "order-close-worker");
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        return props;
    }

    static void title(String text) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(text);
        System.out.println("======================================================================");
    }
}
