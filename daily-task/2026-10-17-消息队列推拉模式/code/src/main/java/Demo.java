import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.GetResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 这个程序用 RabbitMQ 真实 API 演示「消息队列推模式 vs 拉模式」的差别。
 *
 * 生活比喻：推（push）像外卖送上门， broker 主动把消息送到消费者嘴里；
 * 拉（pull）像自己去快递柜取，什么时候去、跑多快全看自己。
 *
 * 三个场景（每条消息都带「生产时刻」，用来算从生产到消费的端到端延迟）：
 *   场景A 推模式，不限流     —— broker 一股脑把消息全糊到消费者脸上
 *   场景B 推模式，QoS=2 限流 —— basicQos 限制「在消费者手里没确认」的条数，其余留在 broker 排队
 *   场景C 拉模式，200ms 轮询 —— basicGet 自己来取，取不到就干等一个轮询间隔
 *
 * 前置条件：先 docker compose up -d 起 RabbitMQ（5673 端口）。
 */
public class Demo {

    private static final String EXCHANGE = "demo.push-pull";
    private static final String ROUTING_KEY = "msg";
    private static final String QUEUE = "demo.push-pull.queue";
    private static final int MESSAGE_COUNT = 20;
    private static final long HANDLE_MILLIS = 50;        // 模拟每条消息的业务处理耗时
    private static final long POLL_INTERVAL_MILLIS = 200; // 拉模式多久来取一次

    private static Connection connection;

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(5673);
        connection = factory.newConnection();
        try {
            Channel main = connection.createChannel();
            main.exchangeDeclare(EXCHANGE, BuiltinExchangeType.DIRECT);
            main.queueDeclare(QUEUE, false, false, false, null);
            main.queueBind(QUEUE, EXCHANGE, ROUTING_KEY);

            pushScenario("场景A：推模式，不限流（没有 QoS）", 0, main);
            pushScenario("场景B：推模式，QoS=2 限流", 2, main);
            pullByPolling(main);

            main.queueDelete(QUEUE);
            main.exchangeDelete(EXCHANGE);
            System.out.println("== 结论：推模式实时性好，但不限制会把消息全糊消费者脸上；"
                    + "拉模式节奏自由，代价是轮询延迟和空转。Kafka 用「拉+长轮询」做到准实时，"
                    + "RabbitMQ 用「推+QoS」防止压垮。选推还是拉，不是技术拍板，是业务说了算 ==");
        } finally {
            connection.close();
        }
    }

    /** 推模式。prefetch <= 0 表示不限流；否则作为 QoS 上限（手里最多留几条没确认的）。 */
    private static void pushScenario(String name, int prefetch, Channel main) throws Exception {
        publishMessages(main);

        Channel consumerChannel = connection.createChannel();
        if (prefetch > 0) {
            consumerChannel.basicQos(prefetch);
        }
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(MESSAGE_COUNT);
        DeliverCallback onMessage = (tag, delivery) -> {
            latencies.add(elapsedMillis(parseSendTime(delivery.getBody())));
            sleepQuietly(HANDLE_MILLIS); // 故意慢吞吞地处理，方便看清积压
            consumerChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            done.countDown();
        };
        CancelCallback onCancel = tag -> System.out.println("消费者被取消了: " + tag);
        consumerChannel.basicConsume(QUEUE, false, onMessage, onCancel);

        sleepQuietly(150); // 等 broker 把消息推过来，随手拍一张「积压在哪」的快照
        int readyWhileConsuming = main.queueDeclarePassive(QUEUE).getMessageCount();
        done.await();

        long sum = 0;
        for (long latency : latencies) {
            sum += latency;
        }
        System.out.println("== " + name + " ==");
        System.out.println("消费 " + MESSAGE_COUNT + " 条：平均端到端延迟 " + (sum / latencies.size())
                + "ms，最后一条 " + latencies.get(latencies.size() - 1) + "ms");
        System.out.println("消费进行中快照：broker 队列里还剩 " + readyWhileConsuming + " 条没推出去"
                + (prefetch > 0
                        ? "（只有 " + prefetch + " 条正在消费者手里，其余留在 broker 排队，谁有空谁接）"
                        : "（不限流，消息已全部推到消费者这边，糊消费者脸上）"));
        consumerChannel.close();
    }

    /** 拉模式：消费者自己 basicGet 来取，取不到就等一个轮询间隔再来。 */
    private static void pullByPolling(Channel main) throws Exception {
        publishMessages(main);

        Channel pullChannel = connection.createChannel();
        int received = 0;
        int emptyPolls = 0;
        long sum = 0;
        int last = 0;
        while (received < MESSAGE_COUNT) {
            GetResponse response = pullChannel.basicGet(QUEUE, false);
            if (response == null) {
                emptyPolls++;
                sleepQuietly(POLL_INTERVAL_MILLIS); // 没取到也是白等，纯属浪费
                continue;
            }
            last = (int) elapsedMillis(parseSendTime(response.getBody()));
            sum += last;
            sleepQuietly(HANDLE_MILLIS); // 取回来也要处理
            pullChannel.basicAck(response.getEnvelope().getDeliveryTag(), false);
            received++;
            sleepQuietly(POLL_INTERVAL_MILLIS); // 多久来取一次，消费者自己定
        }
        // 收满之后再来 3 次空转，直观展示「没新消息时轮询就是在白打工」
        for (int i = 0; i < 3; i++) {
            if (pullChannel.basicGet(QUEUE, false) == null) {
                emptyPolls++;
            }
            sleepQuietly(POLL_INTERVAL_MILLIS);
        }

        System.out.println("== 场景C：拉模式，每 " + POLL_INTERVAL_MILLIS + "ms 来取一次 ==");
        System.out.println("消费 " + MESSAGE_COUNT + " 条：平均端到端延迟 " + (sum / MESSAGE_COUNT)
                + "ms，最后一条 " + last + "ms");
        System.out.println("拉空（白跑）次数 " + emptyPolls + " 次");
        pullChannel.close();
    }

    /** 灌 MESSAGE_COUNT 条消息，消息体带上「生产时刻」，方便算端到端延迟。 */
    private static void publishMessages(Channel main) throws Exception {
        main.queuePurge(QUEUE);
        for (int i = 1; i <= MESSAGE_COUNT; i++) {
            String body = System.nanoTime() + "|" + i;
            main.basicPublish(EXCHANGE, ROUTING_KEY, null, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long parseSendTime(byte[] body) {
        return Long.parseLong(new String(body, StandardCharsets.UTF_8).split("\\|")[0]);
    }

    private static long elapsedMillis(long nanoTime) {
        return (System.nanoTime() - nanoTime) / 1_000_000;
    }

    /** 回调里不能抛出受检异常，统一在辅助方法里吞掉中断。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
