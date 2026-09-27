// 【这个程序在干嘛】
// 题目：第三方（支付、短信平台）回调我们的接口，处理起来又慢、又可能被重复推，
// 要不要把它们丢进 MQ 异步处理？用真实的 Kafka Producer / Consumer 演示这条链路：
//
//   上游（快递员）     producer.send() 把回调写进 topic，然后立刻走人
//   我们（前台）       只回一个「收到了」，不等处理完 —— 这就是异步解耦
//   消费者（后厨分拣） poll() 按自己的速度慢慢取，一条条核对金额、更新订单状态
//   幂等兜底           同一个订单号处理第二遍时直接跳过（门卫只认第一张票），
//                      账本放 Redis，多个消费者实例共享同一份记录
//
// 生产端用 acks=all 保证不丢；消费端关掉自动提交，处理完才 commitSync，
// 崩了重启能重放；真处理不动就重试几次，还不行丢死信队列等人工介入。
//
// 【前置条件】先起 Kafka 和 Redis：docker compose up -d
//             （Kafka localhost:9092、Redis localhost:6379）。
// 编译：mvn -o -q compile   运行：mvn -o exec:java

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

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    static final String TOPIC = "payment-callback";
    static final String BOOTSTRAP_SERVERS = "localhost:9092";
    static final String REDIS_HOST = "localhost";
    static final int REDIS_PORT = 6379;

    /** 上游一共推了多少次（含重复），以及真正干了活的次数。 */
    static final AtomicInteger totalReceived = new AtomicInteger();
    static final AtomicInteger realWorkCount = new AtomicInteger();

    public static void main(String[] args) {
        System.out.println("第三方回调异步化：用真实 Kafka 演示「先收下 → 丢货架 → 异步处理 → 幂等兜底」");

        try (KafkaProducer<String, String> producer = newProducer()) {
            publishCallbacks(producer);
        }
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumeCallbacks(consumer);
        }

        System.out.printf("%n=== 小结 ===%n上游共推来 %d 次消息，真正干活 %d 次，剩下 %d 次重复消息被幂等挡掉了。%n",
                totalReceived.get(), realWorkCount.get(), totalReceived.get() - realWorkCount.get());
        System.out.println("选型口诀（这条消息丢了会不会出事？）：");
        System.out.println("  小流量、单机内部解耦      -> 线程池 + 队列（最省事，但进程一挂队列里的活儿就没了）");
        System.out.println("  怕丢、能容忍几十秒延迟    -> 数据库轮询（最稳，数据落盘不怕重启）");
        System.out.println("  想快又要轻                -> Redis list（lpush 塞、blpop 阻塞取，折中）");
        System.out.println("  要跨系统解耦 + 削峰 + 不丢 -> Kafka 这类正式 MQ，代价是运维成本");
    }

    // ===================== 生产端：把回调丢进货架 =====================
    static KafkaProducer<String, String> newProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");   // 所有副本都写成功才算发出去，别丢消息
        return new KafkaProducer<>(props);
    }

    /** 模拟上游回调：6 次里 T-1001 被重复推了 3 次 —— 对方重试在线上是常态。 */
    static void publishCallbacks(KafkaProducer<String, String> producer) {
        List<String> callbacks = List.of("T-1001", "T-1002", "T-1001", "T-1003", "T-1001", "T-1004");
        long start = System.currentTimeMillis();
        for (String orderNo : callbacks) {
            // 用订单号当 key：同一个订单一定落到同一个分区，顺序有保证
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, orderNo, "{\"orderNo\":\"" + orderNo + "\"}");
            producer.send(record, (metadata, error) -> {
                if (error != null) {
                    System.out.println("  [生产] 发送失败：" + error.getMessage());
                } else {
                    System.out.println("  [生产] 回调已收下 -> partition=" + metadata.partition()
                            + " offset=" + metadata.offset());
                }
            });
        }
        producer.flush();   // 演示用：等消息真的发出去
        System.out.printf("  上游 6 次回调全部收下只花了 %d ms，一条都没等处理完%n",
                System.currentTimeMillis() - start);
    }

    // ===================== 消费端：按自己的速度慢慢处理 =====================
    static KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // 演示方便：每次换一个消费组从头读一遍。线上是固定组名，offset 存在 Kafka 里。
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-worker-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");   // 新组从头开始读
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");     // 手动提交：处理完才算数
        return new KafkaConsumer<>(props);
    }

    static void consumeCallbacks(KafkaConsumer<String, String> consumer) {
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 10_000;   // 演示用：最多等 10 秒
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                handleCallback(record.key(), record.offset());
            }
            if (!records.isEmpty()) {
                consumer.commitSync();   // 这一批真处理完了，offset 才提交，崩了也能重放
            }
        }
    }

    /**
     * 幂等账本放 Redis，多实例共享：消费者可能部署好几个实例，各存各的本地 Set 去重是无效的，
     * 必须用共享存储。SET key 1 NX EX 3600 一条命令同时完成「判断 + 占位」：
     *   返回 "OK"  = 我是第一个来的，继续干活；
     *   返回 null  = 别人已经处理过（key 已存在），直接跳过。
     * EX 3600 给 key 设 1 小时过期，避免账本无限增长。
     */
    static boolean firstTime(String orderNo) {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            String result = jedis.set("mq:consumed:" + orderNo, "1",
                    SetParams.setParams().nx().ex(3600));
            return "OK".equals(result);
        }
    }

    /**
     * 真正处理一条支付回调。关键在第一步幂等：处理过就直接跳过，避免重复扣款、重复发货。
     */
    static void handleCallback(String orderNo, long offset) {
        totalReceived.incrementAndGet();
        if (!firstTime(orderNo)) {
            System.out.printf("  [消费] offset=%d 订单 %s 是重复消息，直接跳过（幂等生效）%n", offset, orderNo);
            return;
        }
        realWorkCount.incrementAndGet();
        System.out.printf("  [消费] offset=%d 处理订单 %s：核对金额、更新订单状态 ... 完成%n", offset, orderNo);
    }
}
