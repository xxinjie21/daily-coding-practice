/*
 * 这个程序在干嘛
 *   用 Netty 起一个真实的 WebSocket 服务端，把「设计一个 IM（即时通讯）协议」要操心的事演示一遍：
 *     ① 连接鉴权   —— 握手就是一次 HTTP 请求，带上 token 才允许升级成 WebSocket
 *     ② 消息有序   —— 同一会话的消息由服务端统一编号（seq），保证收下来是 1、2、3 而不是乱的
 *     ③ ACK 确认   —— 服务端收到就回执，客户端没收到回执就知道该重发
 *     ④ 心跳保活   —— IdleStateHandler 盯着，长时间收不到任何数据就判掉线、关连接
 *     ⑤ 异步解耦   —— 消息投进 RabbitMQ，落库/路由这些慢活不占用 Netty 的 IO 线程
 *   客户端用 JDK 自带的 java.net.http.WebSocket（Java 11 起原生支持），不额外引依赖。
 *
 * 前置条件（先起消息队列，端口见 docker-compose.yml）
 *   cd code && docker compose up -d
 *
 * 运行
 *   mvn -q exec:java
 *
 * 一点说明：原题举例「交给 Kafka 异步处理存储和路由」，本机没有 Kafka 镜像，
 *   改用同类消息队列 RabbitMQ 的真实 API（com.rabbitmq:amqp-client）演示同一件事。
 */
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    static final int PORT = 8899;
    static final String VALID_TOKEN = "tk-user-1001";
    static final String QUEUE = "im-message";
    /** 真实环境心跳 30 秒一次、连丢 3 次才判掉线；这里压成 3 秒，方便当场看到效果 */
    static final int IDLE_SECONDS = 3;
    static final int TYPE_SINGLE = 1, TYPE_GROUP = 2, TYPE_SYSTEM = 3;
    static final String[] TYPE_NAMES = {"", "单聊", "群聊", "系统通知"};

    public static void main(String[] args) throws Exception {
        Connection mq = connectRabbitMq();
        Server server = startWebSocketServer(mq);
        try {
            System.out.println("== WebSocket 服务端已启动：ws://127.0.0.1:" + PORT + "/ws?token=" + VALID_TOKEN + " ==");

            connectWithToken("fake-token", "客户端B");                  // ② 假 token，应该被拒
            Client clientA = connectWithToken(VALID_TOKEN, "客户端A");   // ③ 真 token，连上
            // 前两条是同一个会话，seq 会排成 1、2；后两条各自是新会话，各自从 1 开始
            sendAndWaitAck(clientA, "1001", "1002", TYPE_SINGLE, "在吗？");
            sendAndWaitAck(clientA, "1001", "1002", TYPE_SINGLE, "晚上一起吃饭？");
            sendAndWaitAck(clientA, "1001", "group-888", TYPE_GROUP, "大家晚上好");
            sendAndWaitAck(clientA, "1001", "1001", TYPE_SYSTEM, "你的账号在另一台手机上登录了");

            drainQueue(mq);                                            // ④ 异步落库 + 路由

            System.out.println("== 客户端停止发心跳，等 " + IDLE_SECONDS + " 秒看服务端判定掉线 ==");
            Thread.sleep((IDLE_SECONDS + 1) * 1000L);                   // ⑤ 心跳保活
        } finally {
            server.shutdown();
            mq.close();
        }
    }

    // ---------- 服务端 ----------

    /** Netty 的两个线程组要一起关掉，否则非守护的 IO 线程会让进程不退出 */
    record Server(Channel channel, EventLoopGroup boss, EventLoopGroup worker) {
        void shutdown() throws InterruptedException {
            channel.close().sync();
            boss.shutdownGracefully().sync();
            worker.shutdownGracefully().sync();
        }
    }

    static Server startWebSocketServer(Connection mq) throws InterruptedException {
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // WebSocket 握手就是一次 HTTP 请求，所以先用 HTTP 编解码器
                                .addLast(new HttpServerCodec(), new HttpObjectAggregator(65536))
                                // 读空闲超过 IDLE_SECONDS 就抛事件 —— 心跳丢了的信号
                                .addLast(new IdleStateHandler(0, 0, IDLE_SECONDS))
                                .addLast(new AuthHandler())                        // 握手前先验 token
                                .addLast(new WebSocketServerProtocolHandler(        // 完成协议升级
                                        "/ws", null, true, 65536, false, true))
                                .addLast(new ImMessageHandler(mq));
                    }
                });
        Channel channel = bootstrap.bind(PORT).sync().channel();
        return new Server(channel, boss, worker);
    }

    /** 握手鉴权：token 不对就直接 401，连接根本升不到 WebSocket */
    static class AuthHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof FullHttpRequest request)) {
                ctx.fireChannelRead(msg);
                return;
            }
            String token = new QueryStringDecoder(request.uri())
                    .parameters().getOrDefault("token", List.of("")).get(0);
            if (!VALID_TOKEN.equals(token)) {
                ByteBuf body = Unpooled.copiedBuffer("invalid token", StandardCharsets.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, body);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                ReferenceCountUtil.release(request);
                System.out.println("[鉴权] token 非法（" + token + "），拒绝握手");
                return;
            }
            System.out.println("[鉴权] token 校验通过，允许升级为 WebSocket");
            ctx.fireChannelRead(request);
            ctx.pipeline().remove(this);   // 鉴权只做一次，升完级就把自己从流水线摘掉
        }
    }

    /** 业务处理：给消息编号 → 回 ACK → 投 MQ 异步落库 */
    static class ImMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        private final Connection mq;
        /** 每个会话一个计数器，保证「同一会话」内序号连续；不同会话互不干扰 */
        private final Map<String, AtomicLong> seqBySession = new ConcurrentHashMap<>();

        ImMessageHandler(Connection mq) {
            this.mq = mq;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
            Message in = Message.fromClientText(frame.text());
            String session = in.from() + "->" + in.to();
            long seq = seqBySession.computeIfAbsent(session, k -> new AtomicLong()).incrementAndGet();
            Message stored = new Message(in.from(), in.to(), in.msgType(), in.content(), in.timestamp(), seq);

            // ACK：告诉客户端「收到了，序号是 seq」；客户端迟迟收不到回执就该重发
            ctx.writeAndFlush(new TextWebSocketFrame("ACK|" + seq + "|" + in.content()));

            // 投 MQ：落库、路由这些慢活交给下游消费者，IO 线程立刻解放
            try (var channel = mq.createChannel()) {
                channel.basicPublish("", QUEUE, null, stored.toMqText().getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("[服务端] 收到 " + session + " 的消息，分配 seq=" + seq
                    + "，已投递 MQ：" + in.content());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                System.out.println("[心跳] 超过 " + IDLE_SECONDS + " 秒没收到任何数据，判定掉线，关闭连接");
                ctx.close();
            }
        }
    }

    /**
     * 一条 IM 消息。字段照原题给的结构：from / to / content / timestamp / msgType，
     * 外加服务端分配的 seq。编解码用一个极简的竖线分隔文本协议，肉眼就能看清协议长什么样。
     */
    record Message(String from, String to, int msgType, String content, long timestamp, long seq) {

        /** 客户端上行格式：from|to|msgType|content（seq 和 timestamp 由服务端补） */
        static Message fromClientText(String text) {
            String[] parts = text.split("\\|", 4);
            return new Message(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3],
                    System.currentTimeMillis(), 0);
        }

        /** 投给 MQ 的完整格式，多带了服务端编号和时间戳 */
        String toMqText() {
            return from + "|" + to + "|" + msgType + "|" + timestamp + "|" + seq + "|" + content;
        }
    }

    // ---------- 客户端 ----------

    /** 一个已连上的客户端：socket 用来发，listener 用来等回执 */
    record Client(WebSocket socket, AckListener listener) {
    }

    static Client connectWithToken(String token, String who) {
        AckListener listener = new AckListener(who);
        String url = "ws://127.0.0.1:" + PORT + "/ws?token=" + token;
        try {
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), listener)
                    .get(5, TimeUnit.SECONDS);
            System.out.println("[客户端] " + who + " 连接成功（token=" + token + "）");
            return new Client(socket, listener);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            String detail = cause instanceof WebSocketHandshakeException handshake
                    ? "HTTP " + handshake.getResponse().statusCode() + "（握手被服务端拒绝）"
                    : cause.getMessage();
            System.out.println("[客户端] " + who + " 连接被拒绝：" + detail);
            return null;
        }
    }

    static void sendAndWaitAck(Client client, String from, String to, int msgType, String content)
            throws Exception {
        client.socket().sendText(from + "|" + to + "|" + msgType + "|" + content, true)
                .get(5, TimeUnit.SECONDS);
        System.out.println("[客户端] 已发出（" + TYPE_NAMES[msgType] + "）：" + content);

        String ack = client.listener().acks.poll(5, TimeUnit.SECONDS);
        System.out.println(ack == null
                ? "[客户端] 5 秒没等到 ACK —— 真实实现这里要触发重发"
                : "[客户端] 收到 ACK：" + ack);
    }

    /** JDK 的 Listener 是回调式的：把分片的字符攒成一整条，再丢进队列让主线程取 */
    static class AckListener implements WebSocket.Listener {
        private final String who;
        private final StringBuilder buffer = new StringBuilder();
        final BlockingQueue<String> acks = new LinkedBlockingQueue<>();

        AckListener(String who) {
            this.who = who;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                acks.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);   // 告诉 JDK「再给我下一帧」
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[客户端] " + who + " 连接出错：" + error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[客户端] " + who + " 感知到服务端主动断开连接（close code " + statusCode + "）");
            return null;
        }
    }

    // ---------- 消息队列 ----------

    static Connection connectRabbitMq() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(5673);          // 见 code/docker-compose.yml
        factory.setUsername("guest");
        factory.setPassword("guest");
        Connection connection = factory.newConnection();
        try (var channel = connection.createChannel()) {
            channel.queueDeclare(QUEUE, true, false, false, null);
        }
        return connection;
    }

    /** 模拟下游消费者：把 MQ 里的消息捞出来做落库 + 路由 */
    static void drainQueue(Connection mq) throws Exception {
        System.out.println("== 下游消费者从 MQ 取消息（异步落库 + 路由）==");
        try (var channel = mq.createChannel()) {
            GetResponse response;
            while ((response = channel.basicGet(QUEUE, true)) != null) {
                System.out.println("   [MQ] 落库：" + new String(response.getBody(), StandardCharsets.UTF_8));
            }
        }
    }
}
