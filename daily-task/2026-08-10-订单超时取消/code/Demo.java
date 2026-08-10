// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 「下单 30 分钟不付款就自动关单」，这件事怎么做才不会把系统搞垮？
// 本程序把原题给出的 4 个方案 + 幂等要求，全部用可运行的小例子演示一遍：
//
//   实验1：为什么"直接轮询数据库"不行         —— 全表扫 20 万行 vs 只取到期的 5 条
//   实验2：方案一 RabbitMQ 死信队列(TTL+DLX)   —— 到期自动搬家，以及"队头阻塞"这个坑
//   实验3：方案二 RocketMQ 延迟消息            —— 18 个固定档位，选错档会提前关单
//   实验4：方案三 Redis ZSET + Lua 脚本        —— 不用 Lua 会被两个 worker 抢到同一单
//   实验5：方案四 反面教材 ScheduledExecutor   —— 3 个节点各自定时，同一单被关 3 次
//   实验6：分布式锁 + 状态幂等                 —— 重复消费、用户最后一秒付款，都不能误关
//
// 所有"内存里的中间件"都是最小模拟：RabbitMQ 队列用 ArrayDeque，
// Redis 的 ZSET 用 TreeMap（两者都是"按分数排好序"的结构），
// Redis 的 SETNX 分布式锁用 ConcurrentHashMap.putIfAbsent。
// 目的是把机制讲清楚，不是造一个生产级中间件。
// ============================================================================

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Demo {

    /** 订单超时时间：30 分钟 = 1800 秒。全文都用"秒"当时间单位，方便打印。 */
    static final long TIMEOUT_SECONDS = 30 * 60;

    public static void main(String[] args) throws Exception {
        experiment1_whyPollingDatabaseIsBad();
        experiment2_rabbitMqTtlAndDeadLetter();
        experiment3_rocketMqDelayLevel();
        experiment4_redisZsetNeedsLua();
        experiment5_scheduledExecutorDuplicateInCluster();
        experiment6_idempotentCloseOrder();
        System.out.println("\n全部实验结束。");
    }

    // =========================================================================
    // 实验1：为什么"每分钟 select 一遍订单表"扛不住
    //
    // 大白话：好比快递柜管理员每分钟把 20 万个格子全打开看一遍，
    // 就为了找出其中 5 个超时没取的包裹 —— 白开了 199995 次。
    // =========================================================================
    static void experiment1_whyPollingDatabaseIsBad() {
        printTitle("实验1：轮询数据库 vs 只捞到期的（为什么不能硬扫表）");

        int totalOrders = 200_000;
        long nowSecond = 100_000;            // 假装"现在"是第 100000 秒
        OrderTable orderTable = new OrderTable();
        RedisZsetLike timeoutZset = new RedisZsetLike();

        // 造数据：20 万条订单，其中只有 5 条是 30 分钟前下的（已经超时）
        for (int i = 0; i < totalOrders; i++) {
            // 前 5 条故意造得很早，剩下的都是刚下的单
            long createTime = (i < 5) ? (nowSecond - TIMEOUT_SECONDS - 10) : (nowSecond - i % 60);
            Order order = new Order("ORD" + i, createTime);
            orderTable.insert(order);
            // 同时往 Redis 的 ZSET 里放一份：分数 = 该订单的"到期时刻"
            timeoutZset.zadd(createTime + TIMEOUT_SECONDS, order.orderId);
        }

        // 先热身，让 JIT 把代码编译成机器码，否则第一次跑出来的耗时虚高好几倍
        for (int i = 0; i < 3; i++) {
            orderTable.fullTableScanForTimeout(nowSecond, TIMEOUT_SECONDS);
            timeoutZset.zrangebyscore(0, nowSecond, 100);
        }

        orderTable.resetScanCounter();
        long start = System.nanoTime();
        List<Order> foundByScan = orderTable.fullTableScanForTimeout(nowSecond, TIMEOUT_SECONDS);
        long scanCostUs = (System.nanoTime() - start) / 1000;
        long scannedRows = orderTable.scannedRowCount();

        timeoutZset.resetTouchCounter();
        start = System.nanoTime();
        List<String> foundByZset = timeoutZset.zrangebyscore(0, nowSecond, 100);
        long zsetCostUs = (System.nanoTime() - start) / 1000;
        long touchedEntries = timeoutZset.touchedEntryCount();

        System.out.printf("%-26s %14s %14s %10s%n", "WAY", "TOUCHED_ROWS", "COST(us)", "FOUND");
        System.out.printf("%-26s %14d %14d %10d%n", "A.full-table-scan", scannedRows, scanCostUs, foundByScan.size());
        System.out.printf("%-26s %14d %14d %10d%n", "B.redis-zset-range", touchedEntries, zsetCostUs, foundByZset.size());
        System.out.println("A = 每分钟 select 全表：为了找 5 条，把 20 万行全摸了一遍。");
        System.out.println("B = 按到期时间排好序，只从最早的一端拿：碰几条就是几条。");
        System.out.println("注意：这只是单机内存里的差距，真到数据库上还要叠加磁盘 IO、锁、网络往返。");
    }

    // =========================================================================
    // 实验2：方案一 —— RabbitMQ 死信队列（TTL + DLX）
    //
    // 大白话：下单时先寄一封"限时信"到一个没人收的信箱（TTL 队列），
    // 信过期了邮局不会扔掉，而是自动转投到另一个信箱（死信队列 DLX），
    // 关单服务就守在那个信箱旁边收信。服务重启了信也还在，不会丢。
    //
    // 但它有个真实的坑：RabbitMQ 只盯着队头那封信。
    // 队头没过期，后面早就过期的信也出不来 —— 这就是"队头阻塞"。
    // =========================================================================
    static void experiment2_rabbitMqTtlAndDeadLetter() {
        printTitle("实验2：RabbitMQ 死信队列（TTL + DLX），以及队头阻塞这个坑");

        System.out.println("场景A：一个队列里混着不同 TTL 的订单（错误用法）");
        TtlQueueWithDeadLetter mixedQueue = new TtlQueueWithDeadLetter();
        mixedQueue.send("ORD-普通单", 0, 1800);   // 第 0 秒发出，30 分钟后过期
        mixedQueue.send("ORD-秒杀单", 0, 60);     // 第 0 秒发出，1 分钟后过期
        mixedQueue.send("ORD-活动单", 0, 90);     // 第 0 秒发出，1.5 分钟后过期

        for (long now : new long[]{60, 90, 1800}) {
            List<DelayMessage> arrived = mixedQueue.moveExpiredToDeadLetter(now);
            System.out.printf("  t=%5ds 死信队列收到 %d 条 %s%n", now, arrived.size(), namesOf(arrived));
        }
        System.out.println("  秒杀单第 60 秒就该关，实际拖到第 1800 秒才出来 —— 被队头的普通单堵住了。");

        System.out.println("场景B：按 TTL 分队列，同一个队列里 TTL 全一样（正确用法）");
        TtlQueueWithDeadLetter queue30min = new TtlQueueWithDeadLetter();
        queue30min.send("ORD-A", 0, 1800);
        queue30min.send("ORD-B", 10, 1800);
        queue30min.send("ORD-C", 20, 1800);
        for (long now : new long[]{1800, 1810, 1820}) {
            List<DelayMessage> arrived = queue30min.moveExpiredToDeadLetter(now);
            System.out.printf("  t=%5ds 死信队列收到 %d 条 %s%n", now, arrived.size(), namesOf(arrived));
        }
        System.out.println("  先下的单先到期，队头永远是最早到期的那个，一点都不堵。");
        System.out.println("结论：订单超时取消刚好所有单都是 30 分钟，天然安全；");
        System.out.println("      但只要业务上出现『秒杀单 15 分钟、普通单 30 分钟』，就必须拆成两个队列。");
    }

    // =========================================================================
    // 实验3：方案二 —— RocketMQ 延迟消息
    //
    // 大白话：RocketMQ 不让你随便填延迟时间，只给了 18 个固定档位，
    // 像自动售货机只有 1 元、5 元、10 元的按钮，你想要 7 元买不了。
    // 所以要么把业务时长对齐到某个档，要么"接力投递"分几跳凑出来。
    // =========================================================================
    static void experiment3_rocketMqDelayLevel() {
        printTitle("实验3：RocketMQ 延迟消息的 18 个固定档位");

        System.out.println("官方档位表（level 从 1 开始）：");
        System.out.printf("%-8s %-12s | %-8s %-12s | %-8s %-12s%n",
                "LEVEL", "DELAY", "LEVEL", "DELAY", "LEVEL", "DELAY");
        for (int i = 0; i < 6; i++) {
            System.out.printf("%-8d %-12s | %-8d %-12s | %-8d %-12s%n",
                    i + 1, humanTime(RocketMqDelayLevels.SECONDS[i]),
                    i + 7, humanTime(RocketMqDelayLevels.SECONDS[i + 6]),
                    i + 13, humanTime(RocketMqDelayLevels.SECONDS[i + 12]));
        }

        System.out.println("\n原题里写的 level=6 到底是多久？");
        int seconds6 = RocketMqDelayLevels.secondsOf(6);
        System.out.printf("  level=6  -> %s（原题注成 10min 是笔误，10min 其实是 level=%d）%n",
                humanTime(seconds6), RocketMqDelayLevels.levelOf(600));
        System.out.println("  档位选错的后果：想让用户有 10 分钟付款，结果 2 分钟就把单关了，");
        System.out.println("  用户正在输密码，订单没了 —— 这种事故排查起来还特别难找。");

        System.out.println("\n业务时长怎么对到档位上？");
        for (int want : new int[]{600, 1800, 2700}) {
            int level = RocketMqDelayLevels.levelOf(want);
            if (level > 0) {
                System.out.printf("  想延迟 %-8s -> 正好命中 level=%d%n", humanTime(want), level);
            } else {
                List<Integer> relay = RocketMqDelayLevels.relayPlan(want);
                System.out.printf("  想延迟 %-8s -> 没有对应档位，接力投递 %d 跳：%s%n",
                        humanTime(want), relay.size(), humanList(relay));
                System.out.println("      （每跳到期后，消费者发现还没到点，就再投一条更短的延迟消息）");
            }
        }
    }

    // =========================================================================
    // 实验4：方案三 —— Redis ZSET + Lua 脚本
    //
    // 大白话：ZSET 就是一个"永远按号码排好队的候诊队列"，
    // 号码就是订单的到期时刻。后台线程每秒问一句："有号码小于现在的吗？"
    //
    // 关键在于："查出来"和"删掉"必须是一个动作。
    // 不然两个叫号员同时看到 3 号，就会喊两遍 3 号 —— 订单被关两次。
    // Lua 脚本的作用，就是把"查 + 删"打包成一次不可打断的操作。
    // =========================================================================
    static void experiment4_redisZsetNeedsLua() throws Exception {
        printTitle("实验4：Redis ZSET 取到期任务，为什么一定要配 Lua 脚本");

        int dueOrderCount = 200;
        long nowSecond = 100_000;

        // ---- A：不用 Lua，先 zrangebyscore 查、再 zrem 删（两步，中间有缝） ----
        RedisZsetLike zsetA = fillDueOrders(dueOrderCount, nowSecond);
        ConcurrentHashMap<String, AtomicInteger> grabCountA = new ConcurrentHashMap<>();
        runTwoWorkers(worker -> {
            while (true) {
                List<String> due = zsetA.zrangebyscore(0, nowSecond, 1);   // 第一步：查
                if (due.isEmpty()) {
                    return;
                }
                String orderId = due.get(0);
                Thread.yield();                                            // 就是这条缝，让另一个线程插了进来
                zsetA.zrem(orderId);                                       // 第二步：删
                grabCountA.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
            }
        });

        // ---- B：用 Lua 脚本，查 + 删 一次搞定（原子） ----
        RedisZsetLike zsetB = fillDueOrders(dueOrderCount, nowSecond);
        ConcurrentHashMap<String, AtomicInteger> grabCountB = new ConcurrentHashMap<>();
        runTwoWorkers(worker -> {
            while (true) {
                List<String> due = zsetB.popExpiredByLuaScript(nowSecond, 1); // 一步到位
                if (due.isEmpty()) {
                    return;
                }
                grabCountB.computeIfAbsent(due.get(0), k -> new AtomicInteger()).incrementAndGet();
            }
        });

        System.out.printf("%-34s %14s %14s%n", "WAY", "DUE_ORDERS", "GRABBED_TWICE");
        System.out.printf("%-34s %14d %14d%n", "A.zrangebyscore-then-zrem", dueOrderCount, countDuplicates(grabCountA));
        System.out.printf("%-34s %14d %14d%n", "B.lua-atomic-pop", dueOrderCount, countDuplicates(grabCountB));
        System.out.println("A 里被抢两次的订单，就是会被关两次的订单（关单还要发短信、退优惠券，重复很难看）。");
        System.out.println("B 用 Lua 把两步合一，一条订单只可能被一个 worker 拿到。");
        System.out.println("提醒：ZSET 方案纯靠 Redis 兜底，Redis 挂了这批待关单的任务就没了，");
        System.out.println("      所以原题说它『适合对一致性要求不高的场景』，或者拿数据库做一次兜底补扫。");
    }

    // =========================================================================
    // 实验5：方案四（反面教材）—— 用 ScheduledExecutorService 做分布式定时任务
    //
    // 大白话：三个店员各自戴一块表，到点都跑去关同一扇门。
    // 门只有一扇，短信却发了三条。这就是"节点一多直接重复执行"。
    // =========================================================================
    static void experiment5_scheduledExecutorDuplicateInCluster() throws Exception {
        printTitle("实验5：ScheduledExecutorService 在集群里为什么会重复执行");

        // ---- A：三个节点各跑各的定时器，谁也不知道别人也在跑 ----
        OrderTable tableA = singleTimeoutOrderTable();
        AtomicInteger smsCountA = new AtomicInteger();
        // 这个"集合点"用来模拟三个节点的定时器在同一秒醒来：都查完了，才各自开始关单
        CountDownLatch allNodesFinishedScan = new CountDownLatch(3);
        runThreeNodesWithScheduler(nodeName -> {
            List<Order> timeoutOrders = tableA.fullTableScanForTimeout(100_000, TIMEOUT_SECONDS);
            allNodesFinishedScan.countDown();
            awaitQuietly(allNodesFinishedScan);
            for (Order order : timeoutOrders) {
                order.status.set(OrderStatus.CLOSED);           // 直接 update，不判断原来的状态
                smsCountA.incrementAndGet();                    // 关单副作用：给用户发一条短信
            }
        });

        // ---- B：抢到分布式锁的节点才干活 ----
        OrderTable tableB = singleTimeoutOrderTable();
        AtomicInteger smsCountB = new AtomicInteger();
        FakeRedisLock redisLock = new FakeRedisLock();
        runThreeNodesWithScheduler(nodeName -> {
            // SET lock:close-order <节点名> NX EX 60，抢到才继续，抢不到就这一轮不干活
            if (!redisLock.tryLock("lock:close-order", nodeName)) {
                return;
            }
            try {
                for (Order order : tableB.fullTableScanForTimeout(100_000, TIMEOUT_SECONDS)) {
                    order.status.set(OrderStatus.CLOSED);
                    smsCountB.incrementAndGet();
                }
            } finally {
                redisLock.unlock("lock:close-order", nodeName);
            }
        });

        System.out.printf("%-34s %10s %14s%n", "WAY", "NODES", "SMS_SENT");
        System.out.printf("%-34s %10d %14d%n", "A.each-node-own-timer", 3, smsCountA.get());
        System.out.printf("%-34s %10d %14d%n", "B.timer-plus-redis-lock", 3, smsCountB.get());
        System.out.println("同一条订单，A 发了 3 条『您的订单已取消』，B 只发 1 条。");
        System.out.println("另外 ScheduledExecutorService 是纯内存的：进程一重启，还没到点的任务全没了。");
        System.out.println("所以它只适合单机小工具，不适合扛订单关单这种不能丢的事。");
    }

    // =========================================================================
    // 实验6：分布式锁之外，还必须做"幂等"
    //
    // 大白话：锁只能保证"同一时刻只有一个人动手"，
    // 但消息队列会重投、网络会重试，隔一会儿再来一次照样能动手。
    // 幂等的意思是：同一件事做一百遍，效果和做一遍一样。
    // 做法就一句话：只有当订单还是"待支付"时才允许改成"已关闭"。
    // =========================================================================
    static void experiment6_idempotentCloseOrder() throws Exception {
        printTitle("实验6：重复消费 + 用户最后一秒付款，怎么保证不误关");

        int consumerThreads = 20;

        // ---- 场景一：同一条超时消息被 20 个消费者同时收到 ----
        Order orderA = new Order("ORD-1001", 0);
        AtomicInteger closedTimesA = new AtomicInteger();
        runConcurrently(consumerThreads, () -> closeByCheckThenAct(orderA, closedTimesA));

        Order orderB = new Order("ORD-1002", 0);
        AtomicInteger closedTimesB = new AtomicInteger();
        runConcurrently(consumerThreads, () -> closeByCompareAndSet(orderB, closedTimesB));

        System.out.printf("%-34s %14s %16s%n", "WAY", "CONSUMERS", "CLOSED_TIMES");
        System.out.printf("%-34s %14d %16d%n", "A.check-then-act", consumerThreads, closedTimesA.get());
        System.out.printf("%-34s %14d %16d%n", "B.compare-and-set", consumerThreads, closedTimesB.get());
        System.out.println("A 是『先 select 看看是不是待支付，再 update』，两条语句中间被人插队了。");
        System.out.println("B 对应 SQL：update orders set status=2 where id=? and status=0；");
        System.out.println("  数据库返回受影响行数 1 才算真关上，返回 0 说明别人已经处理过，直接跳过。");

        // ---- 场景二：关单线程和用户支付线程同时发生（200 轮对拍） ----
        int rounds = 200;
        int wrongClosedByCheckThenAct = 0;
        int wrongClosedByCompareAndSet = 0;
        for (int i = 0; i < rounds; i++) {
            Order raceOrder1 = new Order("ORD-RACE-A" + i, 0);
            runPayAndCloseAtSameTime(raceOrder1, false);
            if (raceOrder1.status.get() == OrderStatus.CLOSED && raceOrder1.paidFlag.get()) {
                wrongClosedByCheckThenAct++;    // 钱收了，单却被关了 —— 事故
            }
            Order raceOrder2 = new Order("ORD-RACE-B" + i, 0);
            runPayAndCloseAtSameTime(raceOrder2, true);
            if (raceOrder2.status.get() == OrderStatus.CLOSED && raceOrder2.paidFlag.get()) {
                wrongClosedByCompareAndSet++;
            }
        }
        System.out.printf("%n%-34s %14s %20s%n", "WAY", "ROUNDS", "PAID_BUT_CLOSED");
        System.out.printf("%-34s %14d %20d%n", "A.check-then-act", rounds, wrongClosedByCheckThenAct);
        System.out.printf("%-34s %14d %20d%n", "B.compare-and-set", rounds, wrongClosedByCompareAndSet);
        System.out.println("PAID_BUT_CLOSED = 用户钱已经付了、订单还是被关掉的次数，出一次就是一次投诉。");
    }

    // ---------------- 关单的两种写法 ----------------

    /** 错误写法：先查后改。两条语句中间订单状态可能已经被别人改了。 */
    static void closeByCheckThenAct(Order order, AtomicInteger closedTimes) {
        if (order.status.get() == OrderStatus.WAIT_PAY) {   // 第一步：select 查状态
            // 真实系统里，两条 SQL 之间隔着一次网络往返，少说零点几毫秒。
            // 这里睡 0.05 毫秒，就是把那段"缝隙"如实地摆出来。
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
            order.status.set(OrderStatus.CLOSED);           // 第二步：update（不管现在还是不是待支付）
            closedTimes.incrementAndGet();
        }
    }

    /** 正确写法：一句话完成"查 + 改"，改不动就说明轮不到我。 */
    static void closeByCompareAndSet(Order order, AtomicInteger closedTimes) {
        // 等价 SQL：update orders set status=CLOSED where id=? and status=WAIT_PAY
        boolean success = order.status.compareAndSet(OrderStatus.WAIT_PAY, OrderStatus.CLOSED);
        if (success) {
            closedTimes.incrementAndGet();
        }
    }

    /** 让"用户付款"和"定时关单"这两件事同时发生，看谁把谁盖掉。 */
    static void runPayAndCloseAtSameTime(Order order, boolean useCompareAndSet) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger ignoredCounter = new AtomicInteger();
        Thread payThread = new Thread(() -> {
            awaitQuietly(startGun);
            // 用户付款成功：先标记"钱已到账"，再把状态改成已支付
            if (order.status.compareAndSet(OrderStatus.WAIT_PAY, OrderStatus.PAID)) {
                order.paidFlag.set(true);
            }
        });
        Thread closeThread = new Thread(() -> {
            awaitQuietly(startGun);
            if (useCompareAndSet) {
                closeByCompareAndSet(order, ignoredCounter);
            } else {
                closeByCheckThenAct(order, ignoredCounter);
            }
        });
        payThread.start();
        closeThread.start();
        startGun.countDown();
        payThread.join();
        closeThread.join();
    }

    // ---------------- 各种小工具 ----------------

    static RedisZsetLike fillDueOrders(int count, long nowSecond) {
        RedisZsetLike zset = new RedisZsetLike();
        for (int i = 0; i < count; i++) {
            zset.zadd(nowSecond - 100 + i % 50, "ORD-DUE-" + i);   // 分数都小于 now，即全部已到期
        }
        return zset;
    }

    static void runTwoWorkers(java.util.function.Consumer<String> job) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        Thread w1 = new Thread(() -> { awaitQuietly(startGun); job.accept("worker-1"); });
        Thread w2 = new Thread(() -> { awaitQuietly(startGun); job.accept("worker-2"); });
        w1.start();
        w2.start();
        startGun.countDown();
        w1.join();
        w2.join();
    }

    static int countDuplicates(Map<String, AtomicInteger> grabCount) {
        int duplicated = 0;
        for (AtomicInteger times : grabCount.values()) {
            if (times.get() > 1) {
                duplicated++;
            }
        }
        return duplicated;
    }

    static OrderTable singleTimeoutOrderTable() {
        OrderTable table = new OrderTable();
        table.insert(new Order("ORD-2001", 100_000 - TIMEOUT_SECONDS - 5));  // 一条已经超时的订单
        return table;
    }

    /** 模拟三个部署在不同机器上的节点，各自的定时器在同一秒醒来。 */
    static void runThreeNodesWithScheduler(java.util.function.Consumer<String> tickJob) throws Exception {
        List<ScheduledExecutorService> nodes = new ArrayList<>();
        CountDownLatch allDone = new CountDownLatch(3);
        for (int i = 1; i <= 3; i++) {
            String nodeName = "node-" + i;
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            nodes.add(scheduler);
            scheduler.schedule(() -> {
                try {
                    tickJob.accept(nodeName);
                } finally {
                    allDone.countDown();
                }
            }, 30, TimeUnit.MILLISECONDS);
        }
        allDone.await();
        for (ScheduledExecutorService scheduler : nodes) {
            scheduler.shutdown();
        }
    }

    static void runConcurrently(int threadCount, Runnable job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.execute(() -> {
                awaitQuietly(startGun);
                try {
                    job.run();
                } finally {
                    allDone.countDown();
                }
            });
        }
        startGun.countDown();
        allDone.await();
        pool.shutdown();
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String namesOf(List<DelayMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DelayMessage m : messages) {
            sb.append(sb.length() == 0 ? "" : ", ").append(m.orderId());
        }
        return "[" + sb + "]";
    }

    static String humanList(List<Integer> secondsList) {
        StringBuilder sb = new StringBuilder();
        for (int seconds : secondsList) {
            sb.append(sb.length() == 0 ? "" : " + ").append(humanTime(seconds));
        }
        return sb.toString();
    }

    static String humanTime(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "min";
        }
        return (seconds / 3600) + "h";
    }

    static void printTitle(String title) {
        System.out.println();
        System.out.println("========================================================================");
        System.out.println(title);
        System.out.println("========================================================================");
    }
}

// ============================ 下面是配套的小模型 ============================

/** 订单状态：待支付 -> 已支付 / 已关闭。一旦离开待支付，就不该再被关单。 */
enum OrderStatus {
    WAIT_PAY, PAID, CLOSED
}

/** 一条订单。status 用 AtomicReference，是为了能做"当且仅当旧值是 X 才改成 Y"。 */
class Order {
    final String orderId;
    final long createTimeSecond;
    final AtomicReference<OrderStatus> status = new AtomicReference<>(OrderStatus.WAIT_PAY);
    /** 钱是不是真收到了。用来检查"钱收了但单还是被关掉"这种事故。 */
    final AtomicBoolean paidFlag = new AtomicBoolean(false);

    Order(String orderId, long createTimeSecond) {
        this.orderId = orderId;
        this.createTimeSecond = createTimeSecond;
    }
}

/** 最小版"订单表"：一堆行 + 一个主键索引，并且会统计"这次查询摸了多少行"。 */
class OrderTable {
    private final List<Order> rows = new ArrayList<>();
    private final Map<String, Order> indexById = new ConcurrentHashMap<>();
    private final AtomicInteger scannedRows = new AtomicInteger();

    void insert(Order order) {
        rows.add(order);
        indexById.put(order.orderId, order);
    }

    /** 对应 SQL：select * from orders where status=0 and create_time < now()-30min（没有合适索引，只能全表扫）。 */
    List<Order> fullTableScanForTimeout(long nowSecond, long timeoutSecond) {
        List<Order> timeoutOrders = new ArrayList<>();
        for (Order order : rows) {
            scannedRows.incrementAndGet();     // 每摸一行就计一次数
            boolean waitingPay = order.status.get() == OrderStatus.WAIT_PAY;
            boolean expired = nowSecond - order.createTimeSecond >= timeoutSecond;
            if (waitingPay && expired) {
                timeoutOrders.add(order);
            }
        }
        return timeoutOrders;
    }

    long scannedRowCount() {
        return scannedRows.get();
    }

    void resetScanCounter() {
        scannedRows.set(0);
    }
}

/** RabbitMQ 的一封"限时信"。record 是 Java 16+ 的语法，等价于一个只读的小数据类。 */
record DelayMessage(String orderId, long sendAtSecond, long expireAtSecond) {
}

/**
 * 最小版 RabbitMQ：一个普通队列（消息带 TTL）+ 一个死信队列。
 * 关键点：队列是 FIFO 的，RabbitMQ 只检查队头那条消息有没有过期。
 */
class TtlQueueWithDeadLetter {
    private final ArrayDeque<DelayMessage> ttlQueue = new ArrayDeque<>();
    private final List<DelayMessage> deadLetterQueue = new ArrayList<>();

    /** 下单时发一条消息进来，ttlSecond 后过期。 */
    void send(String orderId, long sendAtSecond, long ttlSecond) {
        ttlQueue.addLast(new DelayMessage(orderId, sendAtSecond, sendAtSecond + ttlSecond));
    }

    /** 时间走到 nowSecond，把已过期的消息从队头搬到死信队列。 */
    List<DelayMessage> moveExpiredToDeadLetter(long nowSecond) {
        List<DelayMessage> movedThisRound = new ArrayList<>();
        // 注意这里是 peek 队头：队头没过期就直接停，后面的再急也得等着
        while (!ttlQueue.isEmpty() && ttlQueue.peekFirst().expireAtSecond() <= nowSecond) {
            DelayMessage expired = ttlQueue.pollFirst();
            deadLetterQueue.add(expired);
            movedThisRound.add(expired);
        }
        return movedThisRound;
    }
}

/** RocketMQ 的 18 个固定延迟档位。 */
class RocketMqDelayLevels {
    /** 官方顺序：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h */
    static final int[] SECONDS = {1, 5, 10, 30, 60, 120, 180, 240, 300, 360,
            420, 480, 540, 600, 1200, 1800, 3600, 7200};

    static int secondsOf(int level) {
        return SECONDS[level - 1];
    }

    /** 想延迟 wantSeconds，能不能正好命中某一档？命中返回 level，命不中返回 -1。 */
    static int levelOf(int wantSeconds) {
        for (int i = 0; i < SECONDS.length; i++) {
            if (SECONDS[i] == wantSeconds) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 命不中档位时的"接力投递"方案：每次挑一个不超过剩余时间的最大档位，
     * 到期后消费者发现还没到点，就按下一跳再投一条。
     */
    static List<Integer> relayPlan(int wantSeconds) {
        List<Integer> hops = new ArrayList<>();
        int remaining = wantSeconds;
        while (remaining > 0) {
            int picked = 0;
            for (int seconds : SECONDS) {
                if (seconds <= remaining) {
                    picked = seconds;      // SECONDS 是升序的，一路覆盖到最大的那个可用档
                }
            }
            if (picked == 0) {
                break;                     // 剩下不到 1 秒，忽略
            }
            hops.add(picked);
            remaining -= picked;
        }
        return hops;
    }
}

/**
 * 最小版 Redis ZSET：按分数（这里是"到期时刻"）排好序的集合。
 * 用 TreeMap 是因为它天生按 key 有序，能从最小的一端往后取，不用扫全量。
 */
class RedisZsetLike {
    private final TreeMap<Long, LinkedHashSet<String>> membersByScore = new TreeMap<>();
    private final Map<String, Long> scoreByMember = new ConcurrentHashMap<>();
    private final AtomicInteger touchedEntries = new AtomicInteger();

    /** zadd key score member */
    synchronized void zadd(long score, String member) {
        membersByScore.computeIfAbsent(score, k -> new LinkedHashSet<>()).add(member);
        scoreByMember.put(member, score);
    }

    /** zrangebyscore key min max limit —— 只查不删。 */
    synchronized List<String> zrangebyscore(long minScore, long maxScore, int limit) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashSet<String>> entry : membersByScore.headMap(maxScore, true).entrySet()) {
            if (entry.getKey() < minScore) {
                continue;
            }
            for (String member : entry.getValue()) {
                touchedEntries.incrementAndGet();   // 只有真正取到的才算"摸过"
                result.add(member);
                if (result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    /** zrem key member */
    synchronized boolean zrem(String member) {
        Long score = scoreByMember.remove(member);
        if (score == null) {
            return false;                            // 已经被别人删走了
        }
        LinkedHashSet<String> bucket = membersByScore.get(score);
        if (bucket != null) {
            bucket.remove(member);
            if (bucket.isEmpty()) {
                membersByScore.remove(score);
            }
        }
        return true;
    }

    /**
     * 模拟一段 Lua 脚本：查出到期的 + 立刻删掉，中间不允许别人插进来。
     * Redis 是单线程执行 Lua 的，这里用 synchronized 表达同样的"不可打断"。
     */
    synchronized List<String> popExpiredByLuaScript(long nowSecond, int limit) {
        List<String> due = zrangebyscore(0, nowSecond, limit);
        for (String member : due) {
            zrem(member);
        }
        return due;
    }

    long touchedEntryCount() {
        return touchedEntries.get();
    }

    void resetTouchCounter() {
        touchedEntries.set(0);
    }
}

/** 最小版 Redis 分布式锁：对应命令 SET key value NX EX 60。 */
class FakeRedisLock {
    private final ConcurrentHashMap<String, String> lockTable = new ConcurrentHashMap<>();

    /** putIfAbsent 就是 NX：键不存在才写进去，写成功的那个人算抢到锁。 */
    boolean tryLock(String lockKey, String ownerName) {
        return lockTable.putIfAbsent(lockKey, ownerName) == null;
    }

    /** 解锁必须带上"我是谁"，免得把别人的锁给删了。 */
    void unlock(String lockKey, String ownerName) {
        lockTable.remove(lockKey, ownerName);
    }
}
