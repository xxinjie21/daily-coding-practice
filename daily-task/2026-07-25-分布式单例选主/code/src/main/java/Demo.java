// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「5 台服务器都跑着同一份程序，怎么保证某段逻辑（比如每天发账单）在整个集群里
//   同一时刻只有一台在跑」。主做法：用 Redis 的 SET key value NX EX 抢分布式锁来选主。
//   程序用 4 个线程模拟 4 台机器，连真实的 Redis 演示三件事：
//     1) 唯一性：同时抢锁，只有 1 台抢到、成为 Leader，其余待命
//     2) 续期：Leader 一边干活一边定时给锁续命，证明自己还活着
//     3) 故障切换：Leader 宕机停止续期，锁过期后另一台自动补位
//
// 前置条件：本机要有 Redis。在本目录执行 `docker compose up -d` 就能起一个。
//
// 生活比喻：一群人抢唯一一个车位（SET NX = 车位空着才停得进）。停进去的人每隔一会儿
//          投一次币续时；他一走（宕机不续费），到点车位自动清空，下一个人就能停进来。

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    static final String REDIS_HOST = "localhost";
    static final int REDIS_PORT = 6379;

    /** 集群里所有机器抢的是同一把锁。 */
    static final String LEADER_LOCK_KEY = "singleton:leader";

    /** 锁 3 秒过期：Leader 突然断电也不会永久占着锁（死锁）。 */
    static final int LOCK_TTL_SECONDS = 3;

    /** 每 1 秒续一次期，只要 Leader 活着，锁就永远不会过期。 */
    static final long RENEW_INTERVAL_MILLIS = 1000;

    /** 续期脚本：只有锁还是我的才延后过期，防止给别人的锁续命。 */
    static final String RENEW_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('expire', KEYS[1], ARGV[2])
            end
            return 0
            """;

    /** 释放脚本：只有锁还是我的才删，防止误删别人刚抢到的锁。 */
    static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("===== 分布式单例（Redis SET NX 抢锁选主）演示 =====\n");
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.del(LEADER_LOCK_KEY);   // 清掉上一轮残留的锁，保证从干净状态开始
        }

        ServerNode[] nodes = new ServerNode[4];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new ServerNode("机器-" + (char) ('A' + i));
            nodes[i].start();
        }

        Thread.sleep(4000);
        System.out.println("\n>>> 让当前 Leader 模拟【宕机】（停止续期），看谁来补位...\n");
        for (ServerNode node : nodes) {
            if (node.isCurrentLeader()) {
                node.crash();
                break;
            }
        }

        Thread.sleep(6000);
        for (ServerNode node : nodes) {
            node.shutdown();
        }
        for (ServerNode node : nodes) {
            node.join();
        }
        System.out.println("\n===== 演示结束 =====");
    }

    /** 一台服务器节点：抢不到锁就当 Follower 待命，抢到就当 Leader 干活并续期。 */
    static class ServerNode extends Thread {
        private final String nodeName;              // 机器名，同时当锁的 owner id

        private volatile boolean running = true;    // 是否在运行（关机置 false）
        private volatile boolean alive = true;      // 是否存活（宕机置 false：停止续期）
        private volatile boolean isLeader = false;

        ServerNode(String nodeName) {
            super(nodeName);
            this.nodeName = nodeName;
        }

        @Override
        public void run() {
            try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                SingletonService service = null;
                long lastRenewAt = 0;

                while (running) {
                    if (!alive) {                       // 已宕机：不抢锁、不续期，安静等被彻底关闭
                        sleepQuietly(200);
                    } else if (isLeader) {              // 我是 Leader：一边干活一边定时续期
                        service.doWork(nodeName);
                        if (System.currentTimeMillis() - lastRenewAt >= RENEW_INTERVAL_MILLIS) {
                            renew(jedis, nodeName);
                            lastRenewAt = System.currentTimeMillis();
                        }
                        sleepQuietly(500);
                    } else if (setIfAbsent(jedis, nodeName)) {
                        isLeader = true;
                        service = new SingletonService(nodeName);  // 单例只在这一刻初始化
                        System.out.println("[选主成功] " + nodeName + " 抢到锁，成为 Leader！");
                        lastRenewAt = System.currentTimeMillis();
                    } else {                            // 抢不到说明已有 Leader，待命等它宕机
                        System.out.println("[待命] " + nodeName + " 抢不到锁，作为 Follower 待命");
                        sleepQuietly(600);
                    }
                }

                // 正常关机时如果自己还是 Leader，主动放锁（别占着不放）
                if (isLeader) {
                    releaseIfOwner(jedis, nodeName);
                }
            }
        }

        /** 抢锁：SET key ownerId NX EX ttl。NX 保证「先到先得」，EX 保证锁会自动过期。 */
        private boolean setIfAbsent(Jedis jedis, String ownerId) {
            String result = jedis.set(LEADER_LOCK_KEY, ownerId,
                    SetParams.setParams().nx().ex(LOCK_TTL_SECONDS));
            return "OK".equals(result);   // 抢到返回 "OK"；key 已被占则返回 null
        }

        /** 续期：走 Lua 脚本，保证「确认锁是我的」和「延后过期」是一个原子动作。 */
        private void renew(Jedis jedis, String ownerId) {
            jedis.eval(RENEW_SCRIPT, List.of(LEADER_LOCK_KEY),
                    List.of(ownerId, String.valueOf(LOCK_TTL_SECONDS)));
        }

        /** 释放锁：同样走 Lua，先确认锁还是我的再删。 */
        private void releaseIfOwner(Jedis jedis, String ownerId) {
            jedis.eval(RELEASE_SCRIPT, List.of(LEADER_LOCK_KEY), List.of(ownerId));
        }

        /** 模拟宕机：停止续期。锁不再被续命，到点自动过期，别的机器就能补位。 */
        void crash() {
            System.out.println("[宕机] " + nodeName + " 崩溃了，不再续期（锁将在 "
                    + LOCK_TTL_SECONDS + " 秒后被别人抢走）");
            alive = false;
            isLeader = false;
        }

        void shutdown() {
            running = false;
        }

        boolean isCurrentLeader() {
            return isLeader;
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

    /** 整个集群里「应该只有一份在跑」的那段业务。 */
    static class SingletonService {
        /** 跨机器统计初始化次数，用来直观证明「4 台机器也只初始化一次」。 */
        static final AtomicInteger initCount = new AtomicInteger();

        SingletonService(String ownerNodeName) {
            System.out.println("    [单例初始化] 第 " + initCount.incrementAndGet()
                    + " 次，由 " + ownerNodeName + " 完成（全集群此刻只此一份）");
        }

        void doWork(String ownerNodeName) {
            System.out.println("    [干活中] " + ownerNodeName + " 作为 Leader 正在执行单例任务");
        }
    }
}
