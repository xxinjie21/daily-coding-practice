import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 迷你配置中心 Demo：演示原题的四个核心机制
 *   ① 配置集中存放：MySQL 真实建表、真实 SQL 持久化（服务端内存里再放一份最新副本扛读）
 *   ② 启动拉全量：客户端引导时 HTTP GET /config 拉全量
 *   ③ 长轮询推变更：客户端 GET /listen 挂住最多 30 秒，配置一改立刻推回
 *   ④ 本地缓存兜底：配置中心挂了，客户端继续用本地缓存，不抛异常
 * 另演示「按分组灰度推送」：改动只推给灰度组，默认组的客户端不会被误推。
 *
 * 前置条件：先 docker compose up -d 起 MySQL（3307，root/root，库 demo），再 exec:java。
 */
public class Demo {

    static final String DB_URL = "jdbc:mysql://127.0.0.1:3307/demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String BASE = "http://127.0.0.1:18080";
    static final long POLL_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) throws Exception {
        Connection db = waitForMySQL();
        ConfigCenter center = new ConfigCenter(db);
        center.start(18080);
        center.publish("default", "app.yml", "order.timeout=30");
        center.publish("gray", "app.yml", "order.timeout=30");

        // 两个客户端：一个默认组，一个灰度组；各自引导拉全量后进入长轮询
        ConfigClient normal = new ConfigClient("normal(default)", "default");
        ConfigClient gray = new ConfigClient("gray", "gray");
        normal.start();
        gray.start();
        Thread.sleep(2000);

        System.out.println("\n>>> 只改灰度组的配置 order.timeout=45");
        center.publish("gray", "app.yml", "order.timeout=45");
        Thread.sleep(3000);

        System.out.println("\n>>> 再改默认组的配置 order.timeout=60");
        center.publish("default", "app.yml", "order.timeout=60");
        Thread.sleep(2000);

        // 模拟配置中心宕机，看客户端是否降级到本地缓存
        System.out.println("\n>>> 配置中心宕机（停在此时，观察客户端降级）");
        center.stop();
        Thread.sleep(3000);

        normal.stop();
        gray.stop();
        db.close();
    }

    // ---------- 配置存储：MySQL 持久化 + 内存最新副本 ----------

    static class ConfigCenter {
        private final Connection db;
        private final HttpServer http;
        /** gkey(group/key) -> 最新值 */private final Map<String, String> latest = new HashMap<>();
        /** gkey -> 版本号，每次发布 +1 */private final Map<String, Long> versions = new HashMap<>();
        /** gkey -> 挂着等变更的长轮询请求 */private final Map<String, List<CompletableFuture<Long>>> waiters = new HashMap<>();

        ConfigCenter(Connection db) throws IOException {
            this.db = db;
            this.http = HttpServer.create(new InetSocketAddress(18080), 0);
        }

        void start(int port) throws SQLException {
            try (Statement st = db.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS config_item("
                        + "gkey VARCHAR(128) PRIMARY KEY, value VARCHAR(1024), version BIGINT)");
            }
            try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT gkey, value, version FROM config_item")) {
                while (rs.next()) {
                    latest.put(rs.getString(1), rs.getString(2));
                    versions.put(rs.getString(1), rs.getLong(3));
                }
            }
            http.createContext("/config", ex -> {
                String gkey = groupKey(ex);
                reply(ex, versions.getOrDefault(gkey, 0L) + "|" + latest.get(gkey));
            });
            http.createContext("/listen", ex -> {
                String gkey = groupKey(ex);
                long clientVersion = Long.parseLong(param(ex, "version"));
                long newVersion = awaitChange(gkey, clientVersion);
                reply(ex, newVersion < 0 ? "NO_CHANGE" : newVersion + "|" + latest.get(gkey));
            });
            http.createContext("/publish", ex -> {
                try {
                    publish(param(ex, "group"), param(ex, "key"), param(ex, "value"));
                    reply(ex, "OK");
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            });
            http.start();
            System.out.println("[server] 配置中心已启动，配置存 MySQL demo.config_item");
        }

        void stop() {
            http.stop(0);
            System.out.println("[server] 配置中心已停止");
        }

        /** 发布配置：先落 MySQL，再换内存副本，最后叫醒所有挂着的长轮询 */
        synchronized void publish(String group, String key, String value) throws SQLException {
            String gkey = group + "/" + key;
            long version = versions.merge(gkey, 1L, (old, one) -> old + 1);
            try (var ps = db.prepareStatement("INSERT INTO config_item(gkey, value, version) VALUES(?, ?, ?) AS v "
                    + "ON DUPLICATE KEY UPDATE value = v.value, version = v.version")) {
                ps.setString(1, gkey);
                ps.setString(2, value);
                ps.setLong(3, version);
                ps.executeUpdate();
            }
            latest.put(gkey, value);
            System.out.println("[server] 发布 " + gkey + " = " + value + "（版本 " + version + "）");
            List<CompletableFuture<Long>> waking;
            synchronized (waiters) {
                waking = waiters.remove(gkey);
            }
            if (waking != null) {
                waking.forEach(f -> f.complete(version));
            }
        }

        /** 长轮询核心：挂住等变更，超时返回 -1 */
        private long awaitChange(String gkey, long clientVersion) {
            synchronized (versions) {
                if (versions.getOrDefault(gkey, 0L) > clientVersion) {
                    return versions.get(gkey);
                }
            }
            var future = new CompletableFuture<Long>();
            synchronized (waiters) {
                waiters.computeIfAbsent(gkey, k -> new ArrayList<>()).add(future);
            }
            try {
                return future.get(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                return -1;
            } finally {
                synchronized (waiters) {
                    List<CompletableFuture<Long>> list = waiters.get(gkey);
                    if (list != null) {
                        list.remove(future);
                    }
                }
            }
        }

        private static String groupKey(com.sun.net.httpserver.HttpExchange ex) {
            return param(ex, "group") + "/" + param(ex, "key");
        }
    }

    // ---------- 客户端：启动拉全量 + 长轮询 + 本地缓存兜底 ----------

    static class ConfigClient {
        private final String name;
        private final String group;
        private final Map<String, String> localCache = new HashMap<>();
        private final OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(POLL_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
                .build();
        private volatile boolean running = true;

        ConfigClient(String name, String group) {
            this.name = name;
            this.group = group;
        }

        void start() {
            Thread thread = new Thread(this::loop, "client-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
        }

        private void loop() {
            String key = "app.yml";
            long version = bootstrap(key);
            while (running) {
                try {
                    // 长轮询：请求挂住，有变更立刻返回，没变更最多等 30 秒
                    String body = get("/listen?group=" + group + "&key=" + key + "&version=" + version);
                    if (!"NO_CHANGE".equals(body)) {
                        version = Long.parseLong(body.substring(0, body.indexOf('|')));
                        localCache.put(key, body.substring(body.indexOf('|') + 1));
                        System.out.println("[client " + name + "] 收到推送，热加载新配置：" + localCache.get(key));
                    }
                } catch (IOException e) {
                    // 配置中心挂了：本地缓存顶班，服务不跟着挂
                    System.out.println("[client " + name + "] 配置中心连不上，降级用本地缓存：" + localCache.get(key));
                    sleepQuietly(2000);
                }
            }
        }

        /** 启动时拉全量配置 */
        private long bootstrap(String key) {
            try {
                String body = get("/config?group=" + group + "&key=" + key);
                localCache.put(key, body.substring(body.indexOf('|') + 1));
                long version = Long.parseLong(body.substring(0, body.indexOf('|')));
                System.out.println("[client " + name + "] 启动拉全量：" + localCache.get(key));
                return version;
            } catch (IOException e) {
                System.out.println("[client " + name + "] 启动拉取失败，本地无缓存可用");
                return 0;
            }
        }

        private String get(String path) throws IOException {
            Request request = new Request.Builder().url(BASE + path).build();
            try (Response response = http.newCall(request).execute()) {
                return response.body().string();
            }
        }
    }

    // ---------- 小工具 ----------

    static String param(com.sun.net.httpserver.HttpExchange ex, String name) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Connection waitForMySQL() {
        for (int i = 0; i < 60; i++) {
            try {
                return DriverManager.getConnection(DB_URL, "root", "root");
            } catch (SQLException e) {
                sleepQuietly(1000);
            }
        }
        throw new IllegalStateException("等不到 MySQL，确认 docker compose up -d 已执行");
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
