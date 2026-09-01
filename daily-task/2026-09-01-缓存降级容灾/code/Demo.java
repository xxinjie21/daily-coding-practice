// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 这个程序在干嘛？
 * 模拟「系统靠 Redis 顶高并发，Redis 突然挂了」时，怎么不让数据库被冲垮。
 * 按原题思路演示四件事：
 *   1) 降级开关：配置中心一个开关，Redis 异常就走"本地缓存/默认值"，保住核心链路。
 *   2) 限流挡穿透：Redis 挂了，请求全砸到 DB，用 Semaphore（只允许 N 个人同时进窗口的闸机）
 *      把打向 DB 的并发数锁在 50 以内，防止雪崩。
 *   3) 多级缓存：本地缓存(Caffeine 思路) + Redis；Redis 倒了，热点还能从本地顶一阵。
 *   4) 持久化恢复：AOF everysec 最多丢 1 秒；纯内存一挂全丢。
 */
public class Demo {

    // ===== 1. 模拟 Redis（会"挂"）=====
    // Redis：一个放在外面、所有服务器共用的"超快小仓库"。
    static class FakeRedis {
        private volatile boolean available = true;
        private final Map<String, String> store = new ConcurrentHashMap<>();
        void down() { available = false; }      // 模拟宕机
        boolean isAvailable() { return available; }
        String get(String k) {
            if (!available) throw new IllegalStateException("Redis 挂了");
            return store.get(k);
        }
        void set(String k, String v) {
            if (!available) throw new IllegalStateException("Redis 挂了");
            store.put(k, v);
        }
    }

    // ===== 2. 本地缓存（Caffeine 的极简版）=====
    // 本地缓存：直接放在自己这台服务器内存里的小仓库，比 Redis 还快，但各服务器不共享。
    // 每条记录带"保质期"，过期就当没有——就像给每份零食标了过期时间。
    static class LocalCache {
        record Entry(String value, long expireAt) {}
        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        String get(String k) {
            Entry e = map.get(k);
            if (e == null) return null;
            if (System.nanoTime() > e.expireAt()) return null; // 过期 = 没命中
            return e.value();
        }
        void put(String k, String v, long ttlMillis) {
            map.put(k, new Entry(v, System.nanoTime() + ttlMillis * 1_000_000L));
        }
    }

    // ===== 3. 配置中心（降级开关）=====
    // 配置中心：一个能随时拨动的全局开关，不用改代码、重启就能改行为。
    static class ConfigCenter {
        private volatile boolean degrade = false; // 平时关，出事拨开
        void openDegrade() { degrade = true; }
        boolean isDegrade() { return degrade; }
    }

    // ===== 4. 数据库（被打会有真实代价）=====
    static class Database {
        private final Map<String, String> table = new ConcurrentHashMap<>();
        private final AtomicInteger concurrent = new AtomicInteger(0);    // 此刻同时在查库的线程数
        private final AtomicInteger maxConcurrent = new AtomicInteger(0); // 历史峰值
        private final AtomicInteger hits = new AtomicInteger(0);
        String load(String key) {
            // 记录"此刻有多少个线程同时在查库"，用来证明信号量真的拦住了
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5); // 模拟一次查库的耗时
                hits.incrementAndGet();
                String v = "data-" + key;
                table.put(key, v);
                return v;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }

    // ===== 5. 业务里的取数方法 =====
    static class CacheService {
        final FakeRedis redis = new FakeRedis();
        final LocalCache local = new LocalCache();
        final ConfigCenter config = new ConfigCenter();
        final Database db = new Database();
        // 信号量 Semaphore：像"只允许 N 个人同时进人工窗口"的闸机，多出来的人排队。
        final Semaphore dbPermit;

        CacheService(int maxDbThreads) {
            this.dbPermit = new Semaphore(maxDbThreads);
        }

        String get(String key) {
            // 降级开关打开 或 Redis 不可用 -> 跳过 Redis，直接走"本地缓存 + 库"
            if (!config.isDegrade() && redis.isAvailable()) {
                String v = redis.get(key);
                if (v != null) return v; // 命中 Redis，最快
            }
            // 第二层：本地缓存（Redis 倒了也能顶热点）
            String v = local.get(key);
            if (v != null) return v;
            // 第三层：查库，但必须先拿到"闸机"许可，控制并发
            dbPermit.acquireUninterruptibly();
            try {
                v = db.load(key);
            } finally {
                dbPermit.release();
            }
            if (v != null) {
                local.put(key, v, 60_000); // 查到的顺手塞回本地缓存
                // 正常态下 Redis 可用，再回写 Redis，下次别人直接命中
                if (!config.isDegrade() && redis.isAvailable()) redis.set(key, v);
            }
            return v;
        }
    }

    // 模拟一批并发请求：n 个线程同时各取一个 key
    static void flood(CacheService svc, List<String> keys) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(keys.size());
        for (String k : keys) {
            pool.submit(() -> svc.get(k));
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 实验1：正常态（Redis 在线）===");
        CacheService normal = new CacheService(50);
        List<String> hot = new ArrayList<>();
        for (int i = 0; i < 20; i++) hot.add("key" + i);
        List<String> reqs = new ArrayList<>();
        for (int i = 0; i < 200; i++) reqs.add(hot.get(i % 20)); // 200 次请求打 20 个热点
        // 先逐个预热（顺序执行，每个 key 只查一次库并写回 Redis），模拟缓存已经热起来的真实状态
        for (String k : hot) normal.get(k);
        flood(normal, reqs);
        System.out.println("DB 被打次数 = " + normal.db.hits.get()
                + " （预热后 200 次请求几乎全部命中 Redis，DB 只被打 20 次，几乎不被碰）");

        System.out.println();
        System.out.println("=== 实验2：Redis 挂了 + 降级开关 + 本地缓存顶 ===");
        CacheService down = new CacheService(50);
        down.redis.down();
        down.config.openDegrade(); // 拨动降级开关
        // 预热到本地缓存：Redis 已挂，请求落到 DB 一次并写回本地缓存
        for (String k : hot) down.get(k);
        flood(down, reqs);
        System.out.println("DB 被打次数 = " + down.db.hits.get()
                + " （预热后热点命中本地缓存，200 次请求 DB 仍只被打 20 次；本地缓存顶住重复请求）");

        System.out.println();
        System.out.println("=== 实验3：限流对比（同样的穿透洪峰，闸机有没有用）===");
        // 200 个互不相同、都没缓存过的 key，全部会穿透到 DB
        List<String> floodKeys = new ArrayList<>();
        for (int i = 0; i < 200; i++) floodKeys.add("fresh" + i);
        CacheService noLimit = new CacheService(1000); // 闸机形同虚设
        noLimit.redis.down(); noLimit.config.openDegrade();
        CacheService limited = new CacheService(50);   // 闸机限 50
        limited.redis.down(); limited.config.openDegrade();
        flood(noLimit, floodKeys);
        flood(limited, floodKeys);
        System.out.println("不限流时同时查库峰值 = " + noLimit.db.maxConcurrent.get() + " 个线程");
        System.out.println("限流 50 时同时查库峰值 = " + limited.db.maxConcurrent.get() + " 个线程（被锁在 50 以内）");

        System.out.println();
        System.out.println("=== 实验4：持久化恢复（宕机后能拿回多少）===");
        int total = 10000;   // 假设写入 1 万条
        int perSec = 1000;   // 写入速率约 1000 条/秒
        // AOF everysec：每秒落盘一次，宕机最多丢最后 1 秒的数据
        int aofRecovered = total - perSec;
        // 纯内存：没落盘，一挂全丢
        int memRecovered = 0;
        System.out.println("AOF everysec 恢复条数 = " + aofRecovered + " （最多丢 1 秒，约 " + perSec + " 条）");
        System.out.println("纯内存模式恢复条数 = " + memRecovered + " （全丢，恢复最久）");

        System.out.println();
        System.out.println("=== 小结 ===");
        System.out.println("Redis 挂了不可怕，怕的是没准备：降级开关保核心、信号量闸机护 DB、多级缓存顶热点、AOF 留后路。");
    }
}
