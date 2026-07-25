// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 演示「分布式单例」的第 1 种思路：用 Redis 的 SET key value NX 抢分布式锁来选主。
// 因为要独立运行、不连真的 Redis，所以我们用一个内存里的 Map 模拟 Redis，
// 用多个线程模拟多台服务器（节点）。程序会演示四件事：
//   1) 唯一性：4 台机器同时抢锁，只有 1 台能抢到、成为 Leader（当家做主）。
//   2) 干活 + 续期：Leader 一边执行单例业务，一边定时给锁"续命"证明自己还活着。
//   3) 故障切换：让 Leader "宕机"（停止续期），锁到期消失后，另一台机器自动补位。
//   4) 状态同步：Leader 通过一个简单的广播，把状态变更通知给其他节点（对应方法3）。
//
// 生活比喻：一群人抢唯一一个车位（SET NX = 车位空着才停得进）。停进去的人
//          每隔一会儿就投一次币续时（续期）；他一走（宕机不续费），到点车位
//          自动清空，下一个人就能停进来。
// ============================================================================

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("===== 分布式单例（Redis SET NX 抢锁选主）演示 =====\n");

        // 所有机器共用的一个"协调中心"，相当于集群里那台大家都能访问的 Redis
        CoordinationStore redis = new CoordinationStore();

        // 起 4 台机器（用线程模拟），让它们几乎同时开机、同时抢锁
        int serverCount = 4;
        ServerNode[] servers = new ServerNode[serverCount];
        for (int i = 0; i < serverCount; i++) {
            servers[i] = new ServerNode("机器-" + (char) ('A' + i), redis);
            servers[i].start();
        }

        // 让集群运行一段时间，观察谁当选 Leader、其他人待命
        Thread.sleep(4000);

        System.out.println("\n>>> 现在让当前 Leader 模拟【宕机】（停止续期），看谁来补位...\n");
        for (ServerNode server : servers) {
            if (server.isCurrentLeader()) {
                server.crash();   // 只让当选的那台"崩溃"
                break;
            }
        }

        // 再运行一段时间，观察锁过期后有没有新 Leader 被选出来
        Thread.sleep(5000);

        // 收工：通知所有机器停机，程序干净退出
        for (ServerNode server : servers) {
            server.shutdown();
        }
        for (ServerNode server : servers) {
            server.join();
        }

        System.out.println("\n===== 演示结束 =====");
    }

    // ========================================================================
    // 协调中心：内存版"Redis"。只实现选主用得到的几个能力，方便看懂。
    // 关键点：所有方法都是 synchronized 的，模拟 Redis 单线程串行执行命令的特性，
    //        这正是"同一时刻只有一个人能抢到锁"的根本保证。
    // ========================================================================
    static class CoordinationStore {
        // key -> 锁记录（谁持有、什么时候过期）
        private final Map<String, LockEntry> lockTable = new ConcurrentHashMap<>();

        // 对应 Redis 命令：SET key ownerId NX EX ttl
        // 语义：这个 key 没被别人占用（或已过期）时，我才占得进去。占进去返回 true。
        synchronized boolean setIfAbsent(String key, String ownerId, long ttlMillis) {
            LockEntry current = lockTable.get(key);
            long now = System.currentTimeMillis();

            boolean isEmpty = (current == null);
            boolean isExpired = (current != null && now > current.expireAtMillis);

            // 车位空着（没人占）或者前一个人的时间到了（过期），我才能停进去
            if (isEmpty || isExpired) {
                lockTable.put(key, new LockEntry(ownerId, now + ttlMillis));
                return true;
            }
            return false; // 有人正占着且没过期，抢锁失败
        }

        // 续期：只有锁还是自己的，才把过期时间往后延（相当于"再投一次币"）
        synchronized boolean renew(String key, String ownerId, long ttlMillis) {
            LockEntry current = lockTable.get(key);
            if (current != null && current.ownerId.equals(ownerId)) {
                current.expireAtMillis = System.currentTimeMillis() + ttlMillis;
                return true;
            }
            return false;
        }

        // 释放锁：先判断这把锁是不是自己的，是才删——避免误删别人刚抢到的锁
        synchronized void releaseIfOwner(String key, String ownerId) {
            LockEntry current = lockTable.get(key);
            if (current != null && current.ownerId.equals(ownerId)) {
                lockTable.remove(key);
            }
        }

        // 一条锁记录：谁持有、什么时刻过期
        static class LockEntry {
            final String ownerId;
            long expireAtMillis;

            LockEntry(String ownerId, long expireAtMillis) {
                this.ownerId = ownerId;
                this.expireAtMillis = expireAtMillis;
            }
        }
    }

    // ========================================================================
    // 单例服务：整个集群里"应该只有一份在跑"的那段业务。
    // 这里用一个静态计数器统计"全集群一共 new 了几次"，用来直观证明：
    // 就算有 4 台机器，真正干活的实例也只会被初始化 1 次（每次选主时）。
    // ========================================================================
    static class SingletonService {
        // 跨"机器"（跨线程）统计初始化次数，AtomicInteger 像个不会数错的取号机
        static final AtomicInteger initCount = new AtomicInteger(0);

        SingletonService(String ownerNodeName) {
            int seq = initCount.incrementAndGet();
            System.out.println("    [单例初始化] 第 " + seq + " 次，由 " + ownerNodeName + " 完成（全集群此刻只此一份）");
        }

        // 单例真正对外提供的业务，这里简单打印代表"在干活"
        void doWork(String ownerNodeName) {
            System.out.println("    [干活中] " + ownerNodeName + " 作为 Leader 正在执行单例任务");
        }
    }

    // ========================================================================
    // 一台服务器节点：不断尝试抢锁；抢到就当 Leader 干活并续期，抢不到就待命重试。
    // ========================================================================
    static class ServerNode extends Thread {
        private static final String LEADER_LOCK_KEY = "singleton_leader"; // 大家抢的是同一把锁
        private static final long LOCK_TTL_MILLIS = 1500;                 // 锁 1.5 秒过期
        private static final long RENEW_INTERVAL_MILLIS = 500;           // 每 0.5 秒续一次期

        private final String nodeName;                 // 机器名，同时当锁的 owner id
        private final CoordinationStore redis;

        private volatile boolean running = true;       // 机器是否在运行（关机置 false）
        private volatile boolean alive = true;         // 是否"存活"（宕机置 false：停止续期）
        private volatile boolean isLeader = false;     // 当前是不是 Leader

        ServerNode(String nodeName, CoordinationStore redis) {
            this.nodeName = nodeName;
            this.redis = redis;
        }

        @Override
        public void run() {
            SingletonService service = null;                 // 当上 Leader 后才初始化
            long lastRenewTime = 0;

            while (running) {
                if (!alive) {
                    // 已经"宕机"：不再抢锁、不再续期，安静等待被彻底关闭
                    sleepQuietly(200);
                    continue;
                }

                if (!isLeader) {
                    // 我还不是 Leader，尝试抢锁（SET NX）
                    boolean won = redis.setIfAbsent(LEADER_LOCK_KEY, nodeName, LOCK_TTL_MILLIS);
                    if (won) {
                        isLeader = true;
                        System.out.println("[选主成功] " + nodeName + " 抢到锁，成为 Leader！");
                        service = new SingletonService(nodeName);   // 单例初始化只在这一刻发生
                        broadcast("我(" + nodeName + ")成为新 Leader，请各节点同步状态"); // 方法3：状态广播
                        lastRenewTime = System.currentTimeMillis();
                    } else {
                        // 抢不到说明已有 Leader，待命一会儿再来碰运气（等它宕机的机会）
                        System.out.println("[待命] " + nodeName + " 未抢到锁，作为 Follower 待命");
                        sleepQuietly(600);
                    }
                } else {
                    // 我是 Leader：一边干活，一边定时续期证明自己还活着
                    service.doWork(nodeName);

                    long now = System.currentTimeMillis();
                    if (now - lastRenewTime >= RENEW_INTERVAL_MILLIS) {
                        redis.renew(LEADER_LOCK_KEY, nodeName, LOCK_TTL_MILLIS);
                        lastRenewTime = now;
                    }
                    sleepQuietly(500);
                }
            }

            // 正常关机时，如果自己还是 Leader，主动释放锁（好习惯：别占着不放）
            if (isLeader) {
                redis.releaseIfOwner(LEADER_LOCK_KEY, nodeName);
            }
        }

        // 模拟宕机：停止续期。锁不会再被续命，到点自动过期，别的机器就能补位。
        void crash() {
            System.out.println("[宕机] " + nodeName + " 崩溃了，不再续期（锁将在过期后被他人抢走）");
            alive = false;
            isLeader = false; // 自己已经不是 Leader 了
        }

        // 正常关机
        void shutdown() {
            running = false;
        }

        boolean isCurrentLeader() {
            return isLeader;
        }

        // 方法3的简单演示：Leader 把状态变更"广播"给集群（真实项目里用 MQ 广播模式）
        private void broadcast(String message) {
            System.out.println("    [广播] " + message);
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}
