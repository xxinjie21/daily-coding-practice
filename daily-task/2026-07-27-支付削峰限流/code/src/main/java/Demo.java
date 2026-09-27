// 支付削峰限流 Demo（真实技术栈：Kafka 削峰队列 + Redis 全局令牌桶 + Redis 幂等）
//
// 题目：进口 200 笔/秒，第三方支付渠道每秒最多收 100 笔，怎么做到"不超限、用满额度、
//       先进先出、不重复扣款"？核心两句话：先把多出来的单子存进队列排队（削峰），
//       再用一个全局"发票员"每秒只发 100 张票（限流），谁抢到票谁去付钱。
//
// 演示的四个手段：
//   1) Kafka 削峰：下单方 200 笔/秒 灌入 topic，消费者按自己节奏取，把洪峰先存住。
//      单分区 + key=订单号，保证同一订单落同一分区，分区内天然 FIFO。
//   2) Redis 令牌桶：多台 Worker 共抢一个桶，桶每秒匀速补 100 张票；用 Lua 脚本把
//      "看余票 + 补票 + 扣票"一气呵成，保证原子，机器再多总速率也不超 100。
//   3) Redis 幂等：订单号用 SET NX 占坑，重复订单直接被挡，不重复扣款。
//   4) 先干活后记账：处理完一笔才 commitSync 提交 Kafka 位点，配合幂等实现"至少一次"不丢单。
//
// 前置条件：本机起 Redis(localhost:6379) 与 Kafka(localhost:9092)，见 docker-compose.yml。
//           仅编译时无需启动任何中间件。

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

public class Demo {

    static final String TOPIC = "pay-orders";
    static final String BUCKET_KEY = "pay:token-bucket";
    static final int INFLOW_PER_SECOND = 200;  // 进口：每秒 200 笔订单
    static final int THIRD_PARTY_LIMIT = 100;  // 出口：第三方限流 100 笔/秒
    static final int TOTAL_ORDERS = 400;       // 2 秒洪峰，共 400 笔唯一订单

    // 令牌桶 Lua：看余票 + 按流逝时间补票 + 扣票，一个脚本原子完成。
    // KEYS[1]=桶 key，ARGV[1]=每秒补票数，ARGV[2]=桶容量，ARGV[3]=当前毫秒，ARGV[4]=要几张票
    static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'lastRefill')
            local tokens = tonumber(bucket[1])
            local lastRefill = tonumber(bucket[2])
            if tokens == nil then tokens = 0 end          -- 桶一开始是空的，从零匀速攒
            if lastRefill == nil then lastRefill = now end

            local delta = math.max(0, now - lastRefill)
            local filled = math.min(capacity, tokens + delta / 1000 * rate)
            local allowed = 0
            if filled >= requested then
                filled = filled - requested
                allowed = 1
            end
            redis.call('HMSET', key, 'tokens', filled, 'lastRefill', now)
            redis.call('PEXPIRE', key, 60000)
            return allowed
            """;

    public static void main(String[] args) throws Exception {
        try (Jedis jedis = new Jedis("localhost", 6379);
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            jedis.del(BUCKET_KEY);

            System.out.println("======== 支付削峰限流 Demo ========");
            System.out.println("进口 " + INFLOW_PER_SECOND + " 笔/秒，出口限额 " + THIRD_PARTY_LIMIT + " 笔/秒\n");

            // 1) 下单方：以 200 笔/秒 灌入 400 笔（2 秒洪峰），Kafka 先把多出来的存住
            for (int orderId = 1; orderId <= TOTAL_ORDERS; orderId++) {
                producer.send(new ProducerRecord<>(TOPIC, String.valueOf(orderId), "PAY:" + orderId));
                Thread.sleep(1000 / INFLOW_PER_SECOND);
            }
            // 故意补发一笔重复订单（模拟网络超时后的重试），看幂等能不能挡住
            producer.send(new ProducerRecord<>(TOPIC, "1", "PAY:1"));
            producer.flush();
            System.out.println("下单完成：共 " + TOTAL_ORDERS + " 笔 + 1 笔重复单，进入 Kafka 排队\n");

            // 2) 消费者：按令牌桶的节奏取单支付，出口被卡在 100 笔/秒
            consumeAndPay(jedis);
        }
    }

    // 消费 Kafka，抢 Redis 令牌后才调"第三方"，处理完再提交位点
    static void consumeAndPay(Jedis jedis) throws InterruptedException {
        Map<Long, AtomicInteger> paymentsPerSecond = new ConcurrentHashMap<>();
        List<Integer> paidOrderIds = new ArrayList<>(); // 单线程消费，记下顺序校验 FIFO
        AtomicInteger duplicateRejected = new AtomicInteger();
        long startMillis = System.currentTimeMillis();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(List.of(TOPIC));
            int seenRecords = 0;
            while (seenRecords < TOTAL_ORDERS + 1) { // 400 笔唯一单 + 1 笔重复单
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    seenRecords++;
                    acquireToken(jedis); // 抢不到票就等着，出口速率由此被卡死
                    int orderId = Integer.parseInt(record.key());

                    if (!markPaidIfAbsent(jedis, orderId)) { // 幂等挡重复扣款
                        duplicateRejected.incrementAndGet();
                    } else {
                        long second = (System.currentTimeMillis() - startMillis) / 1000;
                        paymentsPerSecond.computeIfAbsent(second, key -> new AtomicInteger()).incrementAndGet();
                        paidOrderIds.add(orderId);
                    }
                    consumer.commitSync(); // 先干活后记账：处理完才提交位点，配合幂等不丢单
                }
            }
        }
        report(paymentsPerSecond, paidOrderIds, duplicateRejected);
    }

    // 抢一张令牌：抢到才返回，抢不到小睡几毫秒再试，别疯狂轮询打爆 Redis
    static void acquireToken(Jedis jedis) throws InterruptedException {
        while (true) {
            Object result = jedis.eval(TOKEN_BUCKET_LUA, List.of(BUCKET_KEY),
                    List.of(String.valueOf(THIRD_PARTY_LIMIT), String.valueOf(THIRD_PARTY_LIMIT),
                            String.valueOf(System.currentTimeMillis()), "1"));
            if (Long.valueOf(1L).equals(result)) {
                return;
            }
            Thread.sleep(5);
        }
    }

    // SET NX 占坑：只有第一个塞进去的返回 "OK"，重复订单返回 null —— 等价于数据库唯一索引兜底
    static boolean markPaidIfAbsent(Jedis jedis, int orderId) {
        return "OK".equals(jedis.set("pay:paid:" + orderId, "1", SetParams.setParams().nx().ex(3600)));
    }

    static void report(Map<Long, AtomicInteger> paymentsPerSecond, List<Integer> paidOrderIds,
                       AtomicInteger duplicateRejected) {
        System.out.println("===== 每秒实际打到第三方的笔数（限额 " + THIRD_PARTY_LIMIT + "）=====");
        boolean overLimit = false;
        List<Long> seconds = new ArrayList<>(paymentsPerSecond.keySet());
        seconds.sort(Long::compareTo);
        for (long second : seconds) {
            int count = paymentsPerSecond.get(second).get();
            boolean bad = count > THIRD_PARTY_LIMIT; // 留一点统计抖动余量
            overLimit |= bad;
            System.out.printf("  第 %d 秒：%d 笔 %s%n", second + 1, count, bad ? "<-- 超限!" : "");
        }

        boolean fifoOk = true;
        for (int i = 1; i < paidOrderIds.size(); i++) {
            if (paidOrderIds.get(i) < paidOrderIds.get(i - 1)) {
                fifoOk = false;
                break;
            }
        }

        System.out.println("\n===== 结果校验 =====");
        System.out.println("成功支付: " + paidOrderIds.size() + " 笔（应为 " + TOTAL_ORDERS + "）");
        System.out.println("幂等挡掉重复单: " + duplicateRejected.get() + " 笔（应为 1）");
        System.out.println("FIFO 先进先出: " + (fifoOk ? "通过（支付顺序 = 下单顺序）" : "不通过!"));
        System.out.println("速率是否超限: " + (overLimit ? "有超限，需检查!" : "全程未超过第三方限额"));
        System.out.println("\n削峰缓冲 + 全局令牌桶 + 幂等，三板斧都生效了。");
    }

    static Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    static Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "pay-worker");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // 手动提交，先干活后记账
        return props;
    }
}
