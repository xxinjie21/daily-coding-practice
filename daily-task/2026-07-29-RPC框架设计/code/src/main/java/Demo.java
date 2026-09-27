// 迷你 RPC 框架 Demo：把"调远方的服务"变成"调本地方法"，串起题解里的调用链
//
//   1) 服务暴露与发现：服务端启动后把自己的端口登记到"注册中心"
//      （注册中心用真实 Redis 实现——生产上常用 ZooKeeper/Nacos，这里用 Redis 做个轻量版）
//   2) 动态代理拦截：客户端拿到的 UserService 只是接口，JDK 动态代理当"替身演员"
//   3) 网络通信与序列化：真实 Socket 走本机网络；对象用 JDK 原生序列化（Serializable）变成字节流
//      （真实框架多用 Protobuf/Hessian，体积更小、跨语言）
//   4) 请求唯一 ID + 异步等待：AtomicLong 发号，ConcurrentMap 存"号码 -> 门闩"，
//      CountDownLatch 让调用线程挂起等结果，响应回来按号唤醒
//   5) 容错与负载均衡：轮询在两台服务器之间分摊；一台宕机后自动摘掉并重试另一台
//
// 【前置条件】先起一个 Redis：docker compose up -d（localhost:6379 无密码）。
// 编译：mvn -o -q compile   运行：mvn -o exec:java
//
// 运行效果：起两台用户服务 -> 客户端连调 4 次（轮询分摊）-> 关掉 1 号 -> 再调时失败自动重试到 2 号。

import redis.clients.jedis.Jedis;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    // 请求 / 响应：实现 Serializable，用 JDK 原生序列化在网络上传输
    record RpcRequest(long id, String method, int arg) implements Serializable {}
    record RpcResponse(long id, String result) implements Serializable {}

    // 消费者手里只有这份"菜单"(接口)，真正的"厨房"(实现类)在服务器那边
    public interface UserService {
        String getUser(int userId);
    }

    static class UserServiceImpl implements UserService {
        private final String serverName; // 记住自己是哪台服务器，方便观察负载均衡

        UserServiceImpl(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public String getUser(int userId) {
            return "用户" + userId + "(张三丰) <- 由[" + serverName + "]处理";
        }
    }

    // ============ 注册中心（商家黄页）：真实 Redis，一个服务一个 Set，元素是端口号 ============
    // 多台消费者、多台提供者共享同一份黄页，进程重启黄页也不丢——这正是内存 Map 做不到的。
    static class Registry {
        static final String HOST = "localhost";
        static final int PORT = 6379;
        static final String PREFIX = "rpc:registry:";   // 如 rpc:registry:UserService

        // 服务暴露：把自己的端口 sadd 进这个服务的集合
        static void register(String serviceName, int port) {
            try (Jedis jedis = new Jedis(HOST, PORT)) {
                jedis.sadd(PREFIX + serviceName, String.valueOf(port));
            }
            System.out.println("[注册中心] " + serviceName + " 上线节点，端口=" + port);
        }

        // 服务下线：srem 摘掉这个端口
        static void unregister(String serviceName, int port) {
            try (Jedis jedis = new Jedis(HOST, PORT)) {
                jedis.srem(PREFIX + serviceName, String.valueOf(port));
            }
            System.out.println("[注册中心] " + serviceName + " 下线节点，端口=" + port);
        }

        // 服务发现：smembers 读回当前活着的端口列表
        static List<Integer> lookup(String serviceName) {
            try (Jedis jedis = new Jedis(HOST, PORT)) {
                List<Integer> ports = new ArrayList<>();
                for (String port : jedis.smembers(PREFIX + serviceName)) {
                    ports.add(Integer.parseInt(port));
                }
                Collections.sort(ports);   // 排个序，让轮询的演示结果稳定可读
                return ports;
            }
        }
    }

    // ============ 服务端：监听端口，收到请求就反射调真方法，把结果发回 ============
    static class RpcServer {
        private final int port;
        private final UserService realService;
        private volatile boolean running = true;
        private ServerSocket serverSocket;

        RpcServer(int port, UserService realService) {
            this.port = port;
            this.realService = realService;
        }

        void start() throws Exception {
            serverSocket = new ServerSocket(port);
            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start(); // 每个连接一个线程伺候
                    } catch (Exception e) {
                        // 服务器被关掉时 accept 会抛异常，属正常退出
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
            System.out.println("[服务器" + port + "] 启动完成，等待请求...");
        }

        private void handleClient(Socket client) {
            try (client) {
                // 先建输出流并 flush 握手头，再建输入流，避免两端互相等对方的流头而死锁
                ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(client.getInputStream());
                while (true) {
                    RpcRequest request = (RpcRequest) in.readObject(); // 反序列化出请求
                    // 反射调用真方法：拿"方法名"找到厨房里对应的灶台
                    Method method = UserService.class.getMethod(request.method(), int.class);
                    Object result = method.invoke(realService, request.arg());
                    out.writeObject(new RpcResponse(request.id(), (String) result)); // 带着请求 ID 发回
                    out.flush();
                }
            } catch (Exception e) {
                // 客户端断开等情况，单个连接出错不影响整台服务器
            }
        }

        void stop() throws Exception {
            running = false;
            serverSocket.close();
            System.out.println("[服务器" + port + "] 已停机（模拟宕机）");
        }
    }

    // ============ 客户端：发请求 + 开"收发室"线程收响应，按请求 ID 唤醒等待的人 ============
    static class RpcClient implements AutoCloseable {
        private static final AtomicLong idGenerator = new AtomicLong(); // 取号机：请求号全局唯一
        private final Map<Long, PendingRequest> waitingHall = new ConcurrentHashMap<>(); // 等候大厅
        private final Socket socket;
        private final ObjectOutputStream out;

        // 一次等待 = 一个门闩 + 一个结果格子
        private static class PendingRequest {
            final CountDownLatch latch = new CountDownLatch(1); // 门闩：数到 0 门才开
            volatile String result;
        }

        RpcClient(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            Thread reader = new Thread(this::readResponses); // "收发室"线程，专门盯网线读响应
            reader.setDaemon(true);
            reader.start();
        }

        private void readResponses() {
            try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
                while (true) {
                    RpcResponse response = (RpcResponse) in.readObject();
                    PendingRequest pending = waitingHall.remove(response.id());
                    if (pending != null) {
                        pending.result = response.result();
                        pending.latch.countDown(); // 叫号："42 号，你的结果好了！"
                    }
                }
            } catch (Exception e) {
                // 服务器宕机时读流会断，静默退出，由调用方超时兜底
            }
        }

        String call(String methodName, int arg) throws Exception {
            long requestId = idGenerator.incrementAndGet();
            PendingRequest pending = new PendingRequest();
            waitingHall.put(requestId, pending); // 进等候大厅坐好
            out.writeObject(new RpcRequest(requestId, methodName, arg));
            out.flush();
            // 超时必须设：不设的话服务器卡死，这里的线程就永远挂着
            if (!pending.latch.await(2, TimeUnit.SECONDS)) {
                waitingHall.remove(requestId);
                throw new RuntimeException("等了 2 秒没结果，超时了（服务器可能挂了）");
            }
            return pending.result;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    // ============ 动态代理 + 负载均衡与重试 ============
    static UserService createProxy() {
        AtomicInteger roundRobin = new AtomicInteger(0); // 轮询计数器，像银行叫号把请求摊匀
        InvocationHandler handler = (proxy, method, args) -> {
            Exception lastError = null;
            for (int attempt = 1; attempt <= 2; attempt++) { // 每次调用最多试 2 次
                List<Integer> alivePorts = Registry.lookup("UserService"); // 拉当前活着的节点
                if (alivePorts.isEmpty()) {
                    throw new RuntimeException("注册中心里没有可用节点了！");
                }
                int port = alivePorts.get(Math.abs(roundRobin.getAndIncrement()) % alivePorts.size());
                try (RpcClient client = new RpcClient(port)) {
                    return client.call(method.getName(), (int) args[0]);
                } catch (Exception e) {
                    lastError = e;
                    System.out.println("[代理] 调端口 " + port + " 失败(" + e.getMessage()
                            + ")，把它从黄页摘掉并重试下一台...");
                    Registry.unregister("UserService", port);
                }
            }
            throw new RuntimeException("重试后仍然失败", lastError);
        };
        // Proxy.newProxyInstance：JDK 自带的"替身制造机"
        return (UserService) Proxy.newProxyInstance(
                Demo.class.getClassLoader(), new Class<?>[]{UserService.class}, handler);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== 迷你 RPC 演示开始 ==========\n");

        // 起两台服务器并注册（服务暴露与发现）
        RpcServer server1 = new RpcServer(9001, new UserServiceImpl("服务器A:9001"));
        RpcServer server2 = new RpcServer(9002, new UserServiceImpl("服务器B:9002"));
        server1.start();
        server2.start();
        Registry.register("UserService", 9001);
        Registry.register("UserService", 9002);

        UserService userService = createProxy(); // 消费者拿到的只是代理，用起来和本地对象一样

        System.out.println("\n--- 场景1：连续调用 4 次，观察轮询把请求摊到两台服务器 ---");
        for (int i = 1; i <= 4; i++) {
            System.out.println("第" + i + "次调用结果: " + userService.getUser(i));
        }

        System.out.println("\n--- 场景2：关掉服务器A，再调用，验证'失败重试换一台' ---");
        server1.stop();
        // 黄页里 9001 还在（注册中心不知道它挂了），第一次连它必然失败，
        // 代理会捕获异常、把坏节点摘掉、自动重试服务器B —— 这就是容错
        System.out.println("宕机后调用结果: " + userService.getUser(99));

        server2.stop();
        System.out.println("\n========== 演示结束：调用全部成功，无异常退出 ==========");
    }
}
