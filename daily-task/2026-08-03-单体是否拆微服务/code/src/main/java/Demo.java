// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   用四个能真跑起来的小实验，回答一个问题：「单体项目 QPS 到 1 万了，要不要拆微服务？」
//     实验一：单体三板斧（缓存 / 异步化）能提多少吞吐 —— 性能问题先优化，别急着拆。
//     实验二：拆成微服务要付的两笔账 —— 耗时叠加 + 成功率被乘法拉低。
//     实验三：真要拆，第一步该做的「逻辑拆分」—— 用依赖白名单管住模块之间谁能调谁。
//     实验四：一个简易决策器 —— 输入场景，输出「该优化」还是「该拆分」。
//
//   全程只用 JDK 自带类，不需要任何中间件。
//
// 核心口诀：你痛的是「机器」（CPU 打满、慢查询）→ 优化，别拆；
//           你痛的是「人」（代码互相踩、发版排队）→ 才考虑拆微服务。

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Demo {

    static final int DB_QUERY_MILLIS = 20;      // 一条没走好索引的 SQL 有多慢
    static final int CACHE_HIT_MILLIS = 1;      // 缓存 = 记在手边的小本本，比翻数据库快得多
    static final int SEND_SMS_MILLIS = 30;      // 发短信很慢，但用户其实不用等它
    static final int REQUEST_COUNT = 600;       // 一共模拟多少次请求
    static final int WORKER_THREADS = 50;       // 同时干活的工作线程数，相当于 Tomcat 线程池大小
    static final int HOT_PRODUCT_KINDS = 10;    // 用户只反复看这几款热门商品，所以缓存命中率天然很高

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println(" 单体项目 QPS 到 1 万，要不要拆微服务？");
        System.out.println("==================================================");

        experimentOne_singleAppOptimization();
        experimentTwo_costOfSplitting();
        experimentThree_logicalSplitFirst();
        experimentFour_decisionHelper();

        System.out.println("\n【总结】性能瓶颈优先用缓存、异步、读写分离解决；架构腐化（团队协作打架）才用微服务解耦。");
    }

    // ===================== 实验一：单体不动结构，光靠缓存 + 异步化能提多少吞吐 =====================

    static void experimentOne_singleAppOptimization() throws Exception {
        printTitle("实验一：单体不拆，光加缓存和异步化，吞吐能提多少？");

        long nakedMillis = runRequests(false, false);
        long cachedMillis = runRequests(true, false);
        long cachedAsyncMillis = runRequests(true, true);

        System.out.printf("  ① 裸奔（每次查库 + 同步发短信）    耗时 %4d ms，吞吐约 %6.0f QPS%n",
                nakedMillis, toQps(nakedMillis));
        System.out.printf("  ② 加缓存（热点数据只查一次库）      耗时 %4d ms，吞吐约 %6.0f QPS%n",
                cachedMillis, toQps(cachedMillis));
        System.out.printf("  ③ 缓存 + 异步化（短信丢后台队列）   耗时 %4d ms，吞吐约 %6.0f QPS%n",
                cachedAsyncMillis, toQps(cachedAsyncMillis));

        System.out.printf("%n  结论：一行业务代码都没拆，吞吐提升约 %.1f 倍。%n",
                (double) nakedMillis / Math.max(1, cachedAsyncMillis));
        System.out.println("        单体照样能水平扩容——同一份代码部署 10 台机器，前面挂负载均衡就行。");
        System.out.println("        所以「扛流量」靠加机器 + 缓存，跟拆不拆微服务没关系。");
    }

    /** 跑一轮请求，返回总耗时（毫秒）。useCache 开缓存，useAsync 把发短信丢到后台 */
    static long runRequests(boolean useCache, boolean useAsync) throws Exception {
        Map<Integer, String> cache = new ConcurrentHashMap<>();
        ExecutorService workerPool = Executors.newFixedThreadPool(WORKER_THREADS);
        ExecutorService smsPool = Executors.newFixedThreadPool(4);
        CountDownLatch allDone = new CountDownLatch(REQUEST_COUNT);

        long startNanos = System.nanoTime();
        for (int i = 0; i < REQUEST_COUNT; i++) {
            int productId = i % HOT_PRODUCT_KINDS;
            workerPool.submit(() -> {
                try {
                    if (useCache) {
                        // computeIfAbsent：小本本上有就直接念，没有才去翻数据库大账本
                        cache.computeIfAbsent(productId, Demo::queryProductFromDatabase);
                        sleepQuietly(CACHE_HIT_MILLIS);
                    } else {
                        queryProductFromDatabase(productId);
                    }
                    if (useAsync) {
                        smsPool.submit(() -> sleepQuietly(SEND_SMS_MILLIS));   // 先回用户，短信慢慢发
                    } else {
                        sleepQuietly(SEND_SMS_MILLIS);
                    }
                } finally {
                    allDone.countDown();
                }
            });
        }

        allDone.await();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        workerPool.shutdown();
        // 后台短信可能还没发完——这正是异步化的意义：用户早就拿到响应了
        smsPool.shutdownNow();
        return elapsedMillis;
    }

    static String queryProductFromDatabase(int productId) {
        sleepQuietly(DB_QUERY_MILLIS);
        return "商品-" + productId;
    }

    static double toQps(long elapsedMillis) {
        return REQUEST_COUNT * 1000.0 / Math.max(1, elapsedMillis);
    }

    // ===================== 实验二：拆成微服务要多付哪两笔账 =====================

    static void experimentTwo_costOfSplitting() {
        printTitle("实验二：拆成微服务后，耗时和成功率会怎么变？");

        int simulateCount = 20000;
        int hopCount = 5;
        double availability = 0.999;             // 每个服务自己的可用性：一千次里失败一次
        Random random = new Random(42);          // 固定种子，保证每次运行结果一致，方便对照

        System.out.println("  调用链：网关 → 订单 → 用户 → 库存 → 优惠券 → 支付（共 " + hopCount + " 跳）");
        System.out.println("  模拟 " + simulateCount + " 次下单，单个服务可用性按 99.9% 计算。\n");

        double monolithTotalLatency = 0;
        double microTotalLatency = 0;
        int monolithSuccess = 0;
        int microSuccess = 0;

        for (int i = 0; i < simulateCount; i++) {
            // 单体：5 个环节在一个进程里，方法调用约 1 微秒，可忽略，也不会「网络失败」
            monolithTotalLatency += 2.0 + hopCount * 0.001;
            if (random.nextDouble() < availability) {
                monolithSuccess++;
            }

            // 微服务：每一跳都要序列化 + 走网线，而且每一跳都可能挂
            double microLatency = 2.0;
            boolean microOk = true;
            for (int hop = 0; hop < hopCount; hop++) {
                microLatency += 3.0 + 0.5;                        // 网络往返 3ms + 序列化 0.5ms
                if (random.nextDouble() >= availability) {
                    microOk = false;                             // 只要有一跳挂了，整条链就失败
                }
            }
            microTotalLatency += microLatency;
            if (microOk) {
                microSuccess++;
            }
        }

        System.out.println("  对比结果（平均耗时 / 成功率）：");
        System.out.printf("    单体（进程内调用）  : %7.2f ms   成功率 %.3f%%%n",
                monolithTotalLatency / simulateCount, monolithSuccess * 100.0 / simulateCount);
        System.out.printf("    微服务（跨网络5跳） : %7.2f ms   成功率 %.3f%%%n",
                microTotalLatency / simulateCount, microSuccess * 100.0 / simulateCount);
        System.out.println("\n  两笔账：");
        System.out.println("   ① 耗时账：每跳多 3.5ms，5 跳就白白多出 17.5ms，还没算重试和排队。");
        System.out.printf("   ② 可用性账：0.999 的 %d 次方 ≈ %.4f，故障率直接放大 %d 倍。%n",
                hopCount, Math.pow(availability, hopCount), hopCount);
        System.out.println("   ③ 隐形账：链路追踪、配置中心、熔断限流、分布式事务，每一样都要人维护。");
    }

    // ===================== 实验三：真要拆，第一步是「逻辑拆分」 =====================

    /**
     * 模块注册表：声明「哪个业务模块允许调用哪些模块」。
     * 生活比喻：先在大屋里砌隔断墙，走门要刷卡。刷卡失败说明依赖方向画错了，
     * 趁早改，将来把包搬出去改成 RPC 调用时才不会一团乱。
     */
    static class ModuleRegistry {

        final Map<String, Set<String>> allowedDependencies = new LinkedHashMap<>();

        void declareModule(String moduleName, String... canCallModules) {
            allowedDependencies.put(moduleName, new LinkedHashSet<>(List.of(canCallModules)));
        }

        void callModule(String fromModule, String toModule, String action) {
            if (!allowedDependencies.getOrDefault(fromModule, Set.of()).contains(toModule)) {
                throw new IllegalStateException(
                        "非法依赖：[" + fromModule + "] 不允许调用 [" + toModule + "]，会造成双向耦合，将来拆不开");
            }
            System.out.printf("     ✅ %s → %s：%s%n", fromModule, toModule, action);
        }
    }

    static void experimentThree_logicalSplitFirst() {
        printTitle("实验三：拆之前先做逻辑拆分（模块化单体）");

        ModuleRegistry registry = new ModuleRegistry();
        // 依赖方向必须单向：订单可以问用户和库存，但用户和库存不许反过来找订单
        registry.declareModule("order", "user", "inventory");
        registry.declareModule("user");
        registry.declareModule("inventory");

        System.out.println("  依赖白名单：order → {user, inventory}；user、inventory 不依赖任何模块\n");
        System.out.println("  合法调用：");
        registry.callModule("order", "user", "查询下单人的收货地址");
        registry.callModule("order", "inventory", "扣减库存");

        System.out.println("\n  非法调用（新同事随手写了一行反向依赖）：");
        try {
            registry.callModule("user", "order", "在用户详情页直接查订单列表");
        } catch (IllegalStateException e) {
            System.out.println("     ❌ 被拦下：" + e.getMessage());
            System.out.println("        正确做法：用户域发个「用户已注销」事件，订单域自己去订阅。");
        }
        System.out.println("\n  为什么这一步值钱：依赖关系在一个进程里就理干净了，将来把包搬出去改成 RPC，");
        System.out.println("  业务代码几乎不用重写。隔断墙都没砌就直接搬家，只会搬得鸡飞狗跳。");
    }

    // ===================== 实验四：一个简易决策器 =====================

    /** 一个待判断的场景：瓶颈在哪、团队多大、每周因为发版冲突被卡几次 */
    record Scenario(String name, String bottleneck, int teamSize, int releaseConflictsPerWeek) {}

    static void experimentFour_decisionHelper() {
        printTitle("实验四：到底该优化还是该拆？");

        List<Scenario> scenarios = List.of(
                new Scenario("电商详情页，1 万 QPS 全是读，DB CPU 打满", "数据库", 8, 0),
                new Scenario("秒杀活动，1 万 QPS 砸在同一个商品上", "热点数据", 12, 1),
                new Scenario("QPS 只有 2000，但 40 人团队天天抢着发版", "团队协作", 40, 8),
                new Scenario("QPS 1 万且团队 35 人，发版经常互相等", "团队协作", 35, 6));

        for (Scenario scenario : scenarios) {
            System.out.println("  场景：" + scenario.name());
            System.out.println("    建议：" + advise(scenario) + "\n");
        }
    }

    /** 决策规则：痛的是机器就优化，痛的是人才拆分 */
    static String advise(Scenario scenario) {
        if ("热点数据".equals(scenario.bottleneck())) {
            return "别拆！做本地缓存(Caffeine) + 热点探测 + 分布式锁防超卖。拆微服务在这里只会多几跳网络。";
        }
        if ("数据库".equals(scenario.bottleneck())) {
            return "先优化单体：加 Redis 缓存 + 读写分离 + 慢 SQL 治理 + 水平扩容，等榨干了再谈拆分。";
        }
        if (scenario.teamSize() >= 30 && scenario.releaseConflictsPerWeek() >= 5) {
            return "该拆了，但第一步做「逻辑拆分」：按业务域分包 + 依赖白名单，边界稳定后再物理拆成独立服务。";
        }
        return "还不到时候。先做模块化单体，观察一到两个季度：协作痛点是否持续、边界是否清晰。";
    }

    // ===================== 小工具 =====================

    static void printTitle(String title) {
        System.out.println("\n--------------------------------------------------");
        System.out.println(" " + title);
        System.out.println("--------------------------------------------------");
    }

    /** 睡一会儿，用来模拟「这一步很慢」 */
    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
