// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================ 这个程序在干嘛？ ============================
// 演示一个"迷你 RPC 框架"，把题解.md 里的五个步骤全部串起来跑一遍：
//   1) 服务暴露与发现：服务端启动后把自己(端口)登记到"注册中心"(一个 Map 模拟黄页)
//   2) 动态代理拦截调用：客户端拿到的 UserService 只是接口，JDK 动态代理当"替身演员"
//   3) 网络通信与序列化：用真实的 Socket 走本机网络；序列化用最简单的"竖线分隔字符串"
//      (真实框架用 Protobuf/Hessian，这里为了让新手看懂，用字符串拼接代替)
//   4) 请求唯一 ID + 异步等待：AtomicLong 取号机发号，ConcurrentMap 存"号码->门闩"，
//      CountDownLatch 让调用线程挂起等结果，响应回来按号唤醒
//   5) 容错与负载均衡：轮询(round-robin)在两台服务器之间换着调；一台宕机后自动重试另一台
//
// 运行效果：先起两台"用户服务"服务器 -> 客户端连续调用 4 次(轮询分摊) ->
//           关掉 1 号服务器 -> 再调用时失败自动重试到 2 号 -> 全程无异常退出
// =========================================================================

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    // ======================= 第 0 步：定义"点菜菜单"(服务接口) =======================
    // 消费者手里只有这份菜单(接口)，真正的厨房(实现类)在服务器那边
    public interface UserService {
        String getUser(int userId);
    }

    // 服务端的真实现：真正"做菜"的地方
    public static class UserServiceImpl implements UserService {
        private final String serverName; // 记住自己是哪台服务器，方便观察负载均衡效果

        public UserServiceImpl(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public String getUser(int userId) {
            return "用户" + userId + "(张三丰) <- 由[" + serverName + "]处理";
        }
    }

    // ======================= 第 1 步：注册中心(商家黄页) =======================
    // 真实世界用 ZooKeeper/Nacos，这里用一个 Map 模拟：服务名 -> 可用服务器端口列表
    public static class Registry {
        // CopyOnWriteArrayList：读多写少场景下线程安全的列表(注册/下线少，查询多)
        private static final Map<String, List<Integer>> serviceTable = new ConcurrentHashMap<>();

        // 服务器启动时来登记："我提供 UserService，在 9001 端口"
        public static void register(String serviceName, int port) {
            serviceTable.computeIfAbsent(serviceName, key -> new CopyOnWriteArrayList<>()).add(port);
            System.out.println("[注册中心] " + serviceName + " 上线一个节点，端口=" + port);
        }

        // 服务器宕机/下线时，把它从黄页里划掉
        public static void unregister(String serviceName, int port) {
            List<Integer> ports = serviceTable.get(serviceName);
            if (ports != null) {
                ports.remove(Integer.valueOf(port));
            }
            System.out.println("[注册中心] " + serviceName + " 下线一个节点，端口=" + port);
        }

        // 消费者来拉可用列表(真实框架会缓存这份列表，不每次都查)
        public static List<Integer> lookup(String serviceName) {
            return serviceTable.getOrDefault(serviceName, List.of());
        }
    }

    // ======================= 第 3 步(服务端半边)：RPC 服务器 =======================
    // 监听端口，收到请求就"拆包 -> 反射调真方法 -> 把结果发回去"
    public static class RpcServer {
        private final int port;
        private final UserService realService; // 真正干活的实现类
        private volatile boolean running = true;
        private ServerSocket serverSocket;

        public RpcServer(int port, UserService realService) {
            this.port = port;
            this.realService = realService;
        }

        public void start() throws Exception {
            serverSocket = new ServerSocket(port);
            // 用一个后台线程当"前台接待"，专门等客人连进来
            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        // 每来一个连接就开一个线程伺候它(真实框架用 Netty 的事件循环，更省线程)
                        new Thread(() -> handleClient(client)).start();
                    } catch (Exception e) {
                        // 服务器被关掉时 accept 会抛异常，属于正常退出，不用慌
                        if (running) {
                            System.out.println("[服务器" + port + "] 接待出错: " + e.getMessage());
                        }
                    }
                }
            });
            acceptThread.setDaemon(true); // 守护线程：主程序结束它就跟着结束
            acceptThread.start();
            System.out.println("[服务器" + port + "] 启动完成，等待请求...");
        }

        // 处理一个客户端连接：一行一个请求，格式 "请求ID|方法名|参数"
        private void handleClient(Socket client) {
            try (client;
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {

                String requestLine;
                while ((requestLine = in.readLine()) != null) {
                    // ---- "反序列化"：把字符串拆回 请求ID/方法名/参数 ----
                    String[] parts = requestLine.split("\\|");
                    String requestId = parts[0];
                    String methodName = parts[1];
                    int arg = Integer.parseInt(parts[2]);

                    // ---- 反射调用真方法：拿着"方法名"找到厨房里对应的灶台 ----
                    Method method = UserService.class.getMethod(methodName, int.class);
                    Object result = method.invoke(realService, arg);

                    // ---- 把结果带着"请求ID"发回去，客户端才能对上号 ----
                    out.println(requestId + "|" + result);
                }
            } catch (Exception e) {
                // 客户端断开连接等情况，单个连接出错不影响整台服务器
            }
        }

        public void stop() throws Exception {
            running = false;
            serverSocket.close();
            System.out.println("[服务器" + port + "] 已停机(模拟宕机)");
        }
    }

    // ======================= 第 3、4 步(客户端半边)：RPC 客户端 =======================
    // 负责：连服务器、发请求、开一个"收发室"线程收响应、按请求 ID 唤醒等待的人
    public static class RpcClient {
        // 取号机：给每个请求发一个全宇宙唯一的号(原子递增，多线程同时取也不重号)
        private static final AtomicLong idGenerator = new AtomicLong(0);

        // 等候大厅：请求ID -> 门闩+结果。响应回来按 ID 找到人，把结果塞给他并放行
        private final Map<Long, PendingRequest> waitingHall = new ConcurrentHashMap<>();

        private final Socket socket;
        private final PrintWriter out;

        // 一次等待 = 一个门闩 + 一个结果格子
        private static class PendingRequest {
            final CountDownLatch latch = new CountDownLatch(1); // 门闩：数到 0 门才开
            volatile String result;                              // 结果格子
        }

        public RpcClient(int port) throws Exception {
            this.socket = new Socket("127.0.0.1", port);
            this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            // "收发室"线程：专门盯着网线读响应，读到一条就按 ID 去等候大厅叫号
            Thread readerThread = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = in.readLine()) != null) {
                        String[] parts = responseLine.split("\\|", 2);
                        long requestId = Long.parseLong(parts[0]);
                        PendingRequest pending = waitingHall.remove(requestId);
                        if (pending != null) {
                            pending.result = parts[1]; // 把菜放到对应号码的格子里
                            pending.latch.countDown(); // 开闩：叫号"42号，你的菜好了！"
                        }
                    }
                } catch (Exception e) {
                    // 服务器宕机时读流会断，这里静默退出，由调用方的超时机制兜底
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();
        }

        // 发起一次远程调用：发请求 -> 挂起等结果(最多等 2 秒) -> 返回
        public String call(String methodName, int arg) throws Exception {
            long requestId = idGenerator.incrementAndGet(); // 取号
            PendingRequest pending = new PendingRequest();
            waitingHall.put(requestId, pending);            // 进等候大厅坐好

            out.println(requestId + "|" + methodName + "|" + arg); // 发出请求

            // 关键容错：必须设超时！不设的话服务器卡死，这里的线程就永远挂着了
            boolean gotResult = pending.latch.await(2, TimeUnit.SECONDS);
            if (!gotResult) {
                waitingHall.remove(requestId);
                throw new RuntimeException("等了2秒没结果，超时了(服务器可能挂了)");
            }
            return pending.result;
        }

        public void close() throws Exception {
            socket.close();
        }
    }

    // ======================= 第 2 步：动态代理 + 第 5 步：负载均衡与重试 =======================
    // 造一个"替身演员"：长得像 UserService，实际每次调用都被 invoke() 接管
    public static UserService createProxy() {
        // 轮询计数器：像银行叫号一样，1号窗口、2号窗口轮着来，把请求摊匀
        AtomicInteger roundRobinCounter = new AtomicInteger(0);

        InvocationHandler handler = (Object proxyObject, Method method, Object[] args) -> {
            // 每次调用最多尝试 2 次：第一台失败了就换下一台重试
            Exception lastError = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                // 去注册中心(黄页)拉当前活着的服务器列表
                List<Integer> alivePorts = Registry.lookup("UserService");
                if (alivePorts.isEmpty()) {
                    throw new RuntimeException("注册中心里没有任何可用节点了！");
                }
                // 轮询挑一台：计数器取模，0,1,0,1... 轮着选
                int index = Math.abs(roundRobinCounter.getAndIncrement()) % alivePorts.size();
                int chosenPort = alivePorts.get(index);
                try {
                    RpcClient client = new RpcClient(chosenPort);
                    try {
                        String result = client.call(method.getName(), (int) args[0]);
                        return result;
                    } finally {
                        client.close(); // 演示用完即关；真实框架会复用长连接
                    }
                } catch (Exception e) {
                    lastError = e;
                    System.out.println("[代理] 调端口 " + chosenPort + " 失败(" + e.getMessage()
                            + ")，把它从黄页摘掉并重试下一台...");
                    Registry.unregister("UserService", chosenPort); // 摘掉坏节点
                }
            }
            throw new RuntimeException("重试后仍然失败", lastError);
        };

        // Proxy.newProxyInstance：JDK 自带的"替身制造机"
        return (UserService) Proxy.newProxyInstance(
                Demo.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                handler);
    }

    // ======================= 主流程：把整条链路跑一遍 =======================
    public static void main(String[] args) throws Exception {
        System.out.println("========== 迷你 RPC 演示开始 ==========\n");

        // --- 起两台服务器并注册(服务暴露与发现) ---
        RpcServer server1 = new RpcServer(9001, new UserServiceImpl("服务器A:9001"));
        RpcServer server2 = new RpcServer(9002, new UserServiceImpl("服务器B:9002"));
        server1.start();
        server2.start();
        Registry.register("UserService", 9001);
        Registry.register("UserService", 9002);

        // --- 消费者拿到的只是代理(替身)，用起来和本地对象一模一样 ---
        UserService userService = createProxy();

        System.out.println("\n--- 场景1：连续调用 4 次，观察轮询把请求摊到两台服务器 ---");
        for (int i = 1; i <= 4; i++) {
            String result = userService.getUser(i);
            System.out.println("第" + i + "次调用结果: " + result);
        }

        System.out.println("\n--- 场景2：关掉服务器A，再调用，验证'失败重试换一台' ---");
        server1.stop();
        // 注意：黄页里 9001 还在(注册中心不知道它挂了)，第一次连它必然失败，
        // 代理会捕获异常、把坏节点摘掉、自动重试服务器B —— 这就是容错
        String resultAfterCrash = userService.getUser(99);
        System.out.println("宕机后调用结果: " + resultAfterCrash);

        // --- 收尾 ---
        server2.stop();
        System.out.println("\n========== 演示结束：调用全部成功，无异常退出 ==========");
    }
}
