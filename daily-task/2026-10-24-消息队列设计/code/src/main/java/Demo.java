import java.util.ArrayList;
import java.util.List;

/**
 * 演示「设计一个消息队列」，严格对应原题五条思路：
 *  1) 网络层用 Netty Reactor 多线程收发，IO 不阻塞（原题第 1 条）
 *  2) topic 拆成多个 partition，按 key 哈希分散写入（原题第 1 条）
 *  3) 消息先进内存 RingBuffer（数组 + 原子指针），后台线程异步批量刷 WAL 日志（原题第 2 条）
 *  4) 消费用 Pull：消费者自己带 offset 来拉，想回溯就从头拉（原题第 3 条）
 *  5) 消费者组 offset 落盘 offsets.log，重启后接着消费（原题第 3 条）
 * 副本同步（Raft/双写）和 ZooKeeper 注册中心涉及多机部署，属工程取舍，只在题解里讲，本 Demo 不演示。
 *
 * 前置条件：纯 JDK + Netty，无需中间件，直接 mvn -f code/pom.xml exec:java 运行。
 * 协议（一行一条，\n 结尾，客户端用 Socket 直连）：
 *   PRODUCE <topic> <key> <message>            -> OK <partition> <offset>
 *   FETCH <topic> <partition> <offset> [limit] -> MSG <offset> <message> 若干行 + END <下一个offset>
 *   COMMIT <group> <topic>-<partition> <offset> -> OK（offset 追加写 offsets.log）
 *   OFFSET <group> <topic>-<partition>         -> OK <offset>（没提交过返回 -1）
 */
public class Demo {

    private static final int PORT = 9999;
    private static final int PARTITIONS = 4;
    /** 演示数据放系统临时目录，避免污染仓库；exec:java 的 cwd 是仓库根 */
    private static final java.nio.file.Path DATA_DIR =
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "mq-demo-data");

    public static void main(String[] args) throws Exception {
        resetDataDir();
        Broker broker = new Broker(DATA_DIR);
        broker.startFlusher();
        NettyRef netty = NettyRef.start(broker, PORT);
        Client client = new Client(PORT);

        println("== 场景1：生产者发 8 条消息，按 key 哈希落到 " + PARTITIONS + " 个分区 ==");
        for (int i = 1; i <= 8; i++) {
            // 响应 OK <分区> <offset>：offset 由原子指针分配，同一分区内严格递增不重号
            println("  u" + i + "  ->  " + client.send("PRODUCE orders u" + i + " 订单" + i + "已创建"));
        }

        println("== 场景2：消费者组 g1 用 Pull 拉 partition 0 的前 2 条，并提交 offset ==");
        List<String> first2 = client.sendLines("FETCH orders 0 0 2");
        first2.forEach(line -> println("  拉到: " + line));
        long commitAt = parseEnd(first2);
        println("  提交进度: " + client.send("COMMIT g1 orders-0 " + commitAt)
                + "（offset=" + commitAt + " 已追加写 offsets.log）");

        println("== 场景3：回溯重放，不带消费者组，从 offset 0 再拉一遍 partition 0 ==");
        client.sendLines("FETCH orders 0 0").forEach(line -> println("  重放: " + line));

        println("== 场景4：再发 6 条新消息，然后「重启」broker（停服->刷盘->重新拉起）==");
        for (int i = 9; i <= 14; i++) {
            client.send("PRODUCE orders u" + i + " 订单" + i + "已创建");
        }
        broker.stop();
        netty.shutdown();
        Broker restarted = new Broker(DATA_DIR); // 构造时读 offsets.log，恢复各消费者组进度
        NettyRef netty2 = NettyRef.start(restarted, PORT + 1); // 换端口模拟重启后的新进程
        Client client2 = new Client(PORT + 1);
        long resumed = Long.parseLong(client2.send("OFFSET g1 orders-0").split(" ")[1]);
        println("  重启后查询 g1 的进度 = " + resumed + "（就是场景2 提交的值，没丢）");
        println("  从 " + resumed + " 继续拉 partition 0，只会拿到重启后新生产的消息：");
        client2.sendLines("FETCH orders 0 " + resumed).forEach(line -> println("  续拉: " + line));

        println("== 附：partition 0 的 WAL 日志文件前几行（宕机后可用来恢复）==");
        java.nio.file.Files.readAllLines(DATA_DIR.resolve("orders-0.log")).stream()
                .limit(6).forEach(line -> println("  " + line));

        restarted.stop();
        netty2.shutdown();
    }

    // ---------- 客户端小工具：发一行命令、读响应（MSG 行一直读，END 或非 MSG 首行即停） ----------

    private record Client(int port) {
        String send(String cmd) throws Exception {
            List<String> lines = sendLines(cmd);
            return lines.isEmpty() ? "" : lines.get(0);
        }

        List<String> sendLines(String cmd) throws Exception {
            try (var socket = new java.net.Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                try (var writer = new java.io.OutputStreamWriter(socket.getOutputStream(),
                        java.nio.charset.StandardCharsets.UTF_8);
                     var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                             socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    writer.write(cmd + "\n");
                    writer.flush();
                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        if (!line.startsWith("MSG")) {
                            break;
                        }
                    }
                    return lines;
                }
            }
        }
    }

    private static long parseEnd(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).startsWith("END ")) {
                return Long.parseLong(lines.get(i).substring(4));
            }
        }
        return 0;
    }

    private static void resetDataDir() throws Exception {
        if (java.nio.file.Files.exists(DATA_DIR)) {
            try (var walk = java.nio.file.Files.walk(DATA_DIR)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.delete(p);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        java.nio.file.Files.createDirectories(DATA_DIR);
    }

    private static void println(String s) {
        System.out.println(s);
    }

    // ---------- broker：topic/partition 管理 + 协议处理 + offset 落盘 ----------

    static final class Broker {

        private final java.util.Map<String, List<Partition>> topics =
                new java.util.concurrent.ConcurrentHashMap<>();
        /** "消费者组 topic-分区" -> 已提交 offset，启动时从 offsets.log 恢复 */
        private final java.util.Map<String, Long> committed =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.nio.file.Path offsetLog;
        private final java.util.concurrent.ScheduledExecutorService flusher =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "wal-flusher");
                    t.setDaemon(true);
                    return t;
                });

        Broker(java.nio.file.Path dataDir) throws Exception {
            this.offsetLog = dataDir.resolve("offsets.log");
            restoreFromWal(dataDir);
            if (java.nio.file.Files.exists(offsetLog)) {
                // 重启恢复第二步：把上次各消费者组的进度读回内存
                for (String line : java.nio.file.Files.readAllLines(offsetLog)) {
                    String[] p = line.split(" ");
                    committed.put(p[0] + " " + p[1], Long.parseLong(p[2]));
                }
            }
        }

        /** 重启恢复第一步：扫 WAL 日志文件，把各分区消息读回内存 RingBuffer */
        private void restoreFromWal(java.nio.file.Path dataDir) throws Exception {
            try (var names = java.nio.file.Files.list(dataDir)) {
                for (java.nio.file.Path log : names
                        .filter(p -> p.getFileName().toString().endsWith(".log"))
                        .filter(p -> !p.getFileName().toString().equals("offsets.log"))
                        .sorted().toList()) {
                    String name = log.getFileName().toString().replace(".log", "");
                    String topic = name.substring(0, name.lastIndexOf('-'));
                    int idx = Integer.parseInt(name.substring(name.lastIndexOf('-') + 1));
                    Partition part = new Partition(name, idx, log);
                    for (String line : java.nio.file.Files.readAllLines(log)) {
                        int bar = line.indexOf('|');
                        part.restore(Long.parseLong(line.substring(0, bar)), line.substring(bar + 1));
                    }
                    topics.computeIfAbsent(topic, t -> {
                        List<Partition> list = new ArrayList<>();
                        for (int i = 0; i < PARTITIONS; i++) {
                            list.add(new Partition(t + "-" + i, i, dataDir.resolve(t + "-" + i + ".log")));
                        }
                        return list;
                    }).set(idx, part);
                }
            }
        }

        void startFlusher() {
            // 原题第 2 条：异步批量落盘，写入几乎都在内存完成
            flusher.scheduleAtFixedRate(this::flushAll, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        void stop() {
            flusher.shutdown();
            flushAll(); // 停服前把没刷的消息全部补刷，保证不丢
        }

        private void flushAll() {
            topics.values().forEach(list -> list.forEach(Partition::flush));
        }

        /** 协议处理：返回给客户端的响应文本 */
        String handle(String line) {
            String[] p = line.split(" ", 4);
            try {
                switch (p[0]) {
                    case "PRODUCE": {
                        Partition part = route(p[1], p[2]);
                        return "OK " + part.index + " " + part.produce(p[3]);
                    }
                    case "FETCH": {
                        String[] f = line.split(" ");
                        Partition part = partition(f[1], Integer.parseInt(f[2]));
                        int limit = f.length > 4 ? Integer.parseInt(f[4]) : Integer.MAX_VALUE;
                        StringBuilder sb = new StringBuilder();
                        long next = Long.parseLong(f[3]);
                        for (long o = next; o <= part.publishedPos && limit-- > 0; o++) {
                            sb.append("MSG ").append(o).append(' ').append(part.get(o)).append('\n');
                            next = o + 1;
                        }
                        return sb.append("END ").append(next).toString();
                    }
                    case "COMMIT": {
                        String key = p[1] + " " + p[2];
                        long offset = Long.parseLong(p[3]);
                        committed.put(key, offset);
                        java.nio.file.Files.writeString(offsetLog, key + " " + offset + "\n",
                                java.nio.charset.StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                        return "OK";
                    }
                    case "OFFSET":
                        return "OK " + committed.getOrDefault(p[1] + " " + p[2], -1L);
                    default:
                        return "ERR unknown command";
                }
            } catch (Exception e) {
                return "ERR " + e.getMessage();
            }
        }

        private Partition partition(String topic, int index) {
            List<Partition> list = topics.get(topic);
            if (list == null || index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("no such partition " + topic + "-" + index);
            }
            return list.get(index);
        }

        /** 按 key 哈希选分区：同一个 key 永远落到同一个分区，顺序就有保证 */
        private Partition route(String topic, String key) {
            List<Partition> list = topics.computeIfAbsent(topic, t -> {
                List<Partition> created = new ArrayList<>();
                for (int i = 0; i < PARTITIONS; i++) {
                    created.add(new Partition(t + "-" + i, i, DATA_DIR.resolve(t + "-" + i + ".log")));
                }
                return created;
            });
            return list.get(Math.floorMod(key.hashCode(), list.size()));
        }
    }

    // ---------- partition：内存 RingBuffer + 原子指针 + WAL ----------

    static final class Partition {

        private final int index;
        private final java.nio.file.Path logFile;
        private final String[] ring = new String[64];
        private final java.util.concurrent.atomic.AtomicLong writePos =
                new java.util.concurrent.atomic.AtomicLong(); // 原子写指针：下一条消息的 offset
        private volatile long publishedPos = -1; // 已写完、可被读的最大 offset
        private long flushedPos = -1;            // 已刷到磁盘的最后 offset

        Partition(String name, int index, java.nio.file.Path logFile) {
            this.index = index;
            this.logFile = logFile;
        }

        long produce(String message) {
            long offset = writePos.getAndIncrement();   // 原子占位：多生产者同时写也不重号
            ring[(int) (offset % ring.length)] = message; // 先写槽位
            publishedPos = offset;                       // volatile 写：保证读者能看到槽位内容
            return offset;
        }

        String get(long offset) {
            return ring[(int) (offset % ring.length)];
        }

        /** 重启恢复用：把日志里的一条消息放回环形数组，并推进各指针 */
        void restore(long offset, String message) {
            ring[(int) (offset % ring.length)] = message;
            writePos.set(offset + 1);
            publishedPos = offset;
            flushedPos = offset; // 已落盘的消息不用再刷
        }

        /** 把没落盘的消息追加进 WAL 日志文件，行格式 offset|message */
        synchronized void flush() {
            try {
                while (flushedPos < publishedPos) {
                    String message = get(flushedPos + 1);
                    java.nio.file.Files.writeString(logFile, (flushedPos + 1) + "|" + message + "\n",
                            java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    flushedPos++;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ---------- 网络层：Netty Reactor（boss 1 线程 accept，worker 多线程读写） ----------

    record NettyRef(io.netty.channel.EventLoopGroup boss, io.netty.channel.EventLoopGroup worker) {

        static NettyRef start(Broker broker, int port) throws InterruptedException {
            var boss = new io.netty.channel.nio.NioEventLoopGroup(1);
            var worker = new io.netty.channel.nio.NioEventLoopGroup();
            var bootstrap = new io.netty.bootstrap.ServerBootstrap();
            bootstrap.group(boss, worker)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                    .childHandler(new io.netty.channel.ChannelInitializer<>() {
                        @Override
                        protected void initChannel(io.netty.channel.Channel ch) {
                            // 按行切割 + 字符串编解码，协议演示最直白
                            ch.pipeline().addLast(
                                    new io.netty.handler.codec.LineBasedFrameDecoder(1024),
                                    new io.netty.handler.codec.string.StringDecoder(),
                                    new io.netty.handler.codec.string.StringEncoder(),
                                    new io.netty.channel.SimpleChannelInboundHandler<String>() {
                                        @Override
                                        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,
                                                                    String line) {
                                            ctx.writeAndFlush(broker.handle(line) + "\n");
                                        }
                                    });
                        }
                    });
            bootstrap.bind(port).sync();
            return new NettyRef(boss, worker);
        }

        void shutdown() throws InterruptedException {
            worker.shutdownGracefully().sync();
            boss.shutdownGracefully().sync();
        }
    }
}
