/**
 * 配置中心挂了，微服务怎么保命 —— 本地缓存 + 默认值 + 熔断 三件套。
 *
 * 按原题四条线逐一演示：
 *   ① 启动时拉全量配置，写到本地文件 config-cache.json（配置中心没了，重启还能凭它起来）
 *   ② 运行中内存持有 + 定时轮询刷新，有变更就同步更新本地文件
 *   ③ get(key, 默认值) 兜底，对应 Spring 的 @Value("${xx:default}") 语义
 *   ④ Resilience4j 真实熔断器：连续失败自动打开，之后请求根本发不出去，直接用内存缓存
 *
 * 无需外部中间件：配置中心就是本进程内的一个真实 HTTP 服务（com.sun.net.httpserver，端口 8899），
 * 客户端用 OkHttp 真实调用。跑法：mvn -B -o -q compile 然后 mvn exec:java
 */
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Demo {

    static final int PORT = 8899;
    /** 本地缓存文件：对应 Apollo/Nacos 的 config-cache.json */
    static final Path CACHE_FILE = Path.of("target", "config-cache.json");

    public static void main(String[] args) throws Exception {
        ConfigCenter center = new ConfigCenter();
        center.start();

        // ① 客户端启动：拉全量 + 写本地文件
        ConfigClient client = new ConfigClient();
        client.startup();
        System.out.println("启动后内存配置: " + client.snapshot());
        System.out.println("本地缓存文件内容: " + Files.readString(CACHE_FILE));

        // ② 配置中心发布变更 → 客户端轮询拉到新值，内存和本地文件一起更新
        sleep(300);
        center.publish("timeout.ms", "800");
        System.out.println("\n[配置中心] 把 timeout.ms 改成 800");
        client.pollOnce();

        // ③ 配置中心宕机 → 连续轮询失败 → 熔断器打开 → 之后请求发不出去，直接用内存缓存
        System.out.println("\n--- 配置中心宕机，客户端还在每 2 秒轮询 ---");
        center.stop();
        for (int i = 0; i < 3; i++) {
            sleep(2000);
            client.pollOnce();
        }
        client.pollOnce();
        System.out.println("此刻熔断器状态: " + client.breakerState());

        // ④ 等过半开等待期再探一次：服务还没好 → 探测失败 → 熔断重新打开
        sleep(4500);
        System.out.println("\n--- 过半开等待期，熔断器放行一次探测 ---");
        client.pollOnce();
        System.out.println("探测后熔断器状态: " + client.breakerState());

        // ⑤ 配置中心重启 → 半开探测成功 → 熔断关闭 → 恢复正常拉取
        sleep(4500);
        System.out.println("\n--- 配置中心重启 ---");
        center.start();
        sleep(4500);
        client.pollOnce();
        System.out.println("恢复后熔断器状态: " + client.breakerState());
        sleep(300);
        center.publish("db.pool.size", "50");
        System.out.println("[配置中心] 把 db.pool.size 改成 50");
        client.pollOnce();

        // ⑥ 再挂一次 + 客户端重启：配置中心不可达，凭本地缓存文件也能把服务拉起来
        System.out.println("\n--- 配置中心再次宕机，客户端重启 ---");
        center.stop();
        ConfigClient restarted = new ConfigClient();
        restarted.startup();
        System.out.println("重启后加载到的配置: " + restarted.snapshot());

        // ⑦ 默认值兜底：没配过的 key 用默认值，配过的 key 用真实值
        System.out.println("没配过的 key: " + restarted.get("cache.warmup.enabled", "false"));
        System.out.println("配过的 key:   " + restarted.get("timeout.ms", "500"));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把异常压缩成一行可读信息，方便看「到底为什么失败」 */
    static String brief(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** 配置中心：一个真实的 HTTP 服务，只提供 GET /config 返回全量配置 */
    static class ConfigCenter {
        private final Map<String, String> configs = new LinkedHashMap<>();
        private final ObjectMapper mapper = new ObjectMapper();
        private HttpServer server;
        private ExecutorService pool;

        ConfigCenter() {
            configs.put("timeout.ms", "500");
            configs.put("db.pool.size", "20");
            configs.put("feature.newCart", "on");
        }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/config", exchange -> {
                byte[] body = mapper.writeValueAsBytes(configs);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            // 守护线程池：server.stop 后 exec:java 不会被这些线程拖住不放
            pool = Executors.newFixedThreadPool(4, run -> {
                Thread thread = new Thread(run);
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(pool);
            server.start();
            System.out.println("[配置中心] 启动 http://127.0.0.1:" + PORT);
        }

        void stop() {
            server.stop(0);
            pool.shutdownNow();
            System.out.println("[配置中心] 宕机");
        }

        /** 模拟运维在配置中心后台改配置 */
        void publish(String key, String value) {
            configs.put(key, value);
        }
    }

    /** 微服务侧的配置客户端：本地缓存 + 内存 + 默认值 + 熔断 */
    static class ConfigClient {
        private static final String BASE_URL = "http://127.0.0.1:" + PORT;

        private final OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
        private final ObjectMapper mapper = new ObjectMapper();
        private final CircuitBreaker breaker = CircuitBreaker.of("config-center",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(3)   // 不满 3 次不算账，防网络抖两下就误打开
                        .failureRateThreshold(50)  // 失败率过半就打开
                        .waitDurationInOpenState(Duration.ofSeconds(4)) // 原题 30s，演示压缩成 4s
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build());

        private Map<String, String> memory = new LinkedHashMap<>();

        /** 启动：优先从配置中心拉；拉不到就退而读本地缓存文件；再没有就只剩默认值 */
        void startup() throws IOException {
            try {
                String body = fetchAll();
                memory = mapper.readValue(body, new TypeReference<Map<String, String>>() {});
                Files.writeString(CACHE_FILE, body); // 原题①：启动时把配置落到本地文件
                System.out.println("启动：从配置中心拉到全量配置，并写入本地缓存文件");
            } catch (Exception e) {
                if (Files.exists(CACHE_FILE)) {
                    memory = mapper.readValue(Files.readString(CACHE_FILE),
                            new TypeReference<Map<String, String>>() {});
                    System.out.println("启动：配置中心不可达（" + brief(e) + "），改从本地缓存文件加载");
                } else {
                    System.out.println("启动：配置中心和本地缓存都没有，只能用代码里的默认值");
                }
            }
        }

        /** 定时轮询（原题②）：成功就更新内存，有变更再同步本地文件；失败交给熔断器和内存缓存兜底 */
        void pollOnce() {
            String stateBefore = breaker.getState().name();
            try {
                String body = breaker.executeCallable(this::fetchAll);
                Map<String, String> remote = mapper.readValue(body,
                        new TypeReference<Map<String, String>>() {});
                if (!remote.equals(memory)) {
                    memory = remote;
                    Files.writeString(CACHE_FILE, body); // 有更新才动本地文件
                    System.out.println("[轮询] 拉到新配置（熔断器 " + stateBefore + "）: " + memory);
                } else {
                    System.out.println("[轮询] 配置无变化（熔断器 " + stateBefore + "）");
                }
            } catch (CallNotPermittedException e) {
                // 熔断器 OPEN：请求直接拦在本地，根本发不出去 —— 这就是原题④的「拦住配置获取请求」
                System.out.println("[轮询] 熔断器 OPEN：请求不发出去，直接用内存缓存 " + memory);
            } catch (Exception e) {
                if ("OPEN".equals(stateBefore)) {
                    System.out.println("[轮询] 半开探测失败，熔断重新打开（" + brief(e) + "）");
                } else {
                    System.out.println("[轮询] 远程拉取失败，内存缓存继续顶上（" + brief(e) + "）");
                }
            }
        }

        /** 原题③：拿不到就用默认值，对应 @Value("${xx:default}") */
        String get(String key, String defaultValue) {
            return memory.getOrDefault(key, defaultValue);
        }

        Map<String, String> snapshot() {
            return memory;
        }

        String breakerState() {
            return breaker.getState().name();
        }

        private String fetchAll() throws IOException {
            Request request = new Request.Builder().url(BASE_URL + "/config").get().build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                return response.body().string();
            }
        }
    }
}
