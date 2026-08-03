// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
// 它用四个能真跑起来的小实验，回答一个问题：
//   「单体项目 QPS 到 1 万了，要不要拆微服务？」
//
// 实验一：单体三板斧（缓存 / 异步化）到底能提多少吞吐 —— 证明"性能问题先优化，别急着拆"。
// 实验二：拆成微服务后要付的两笔账 —— 耗时叠加 + 成功率被乘法拉低。
// 实验三：真要拆，第一步该做的"逻辑拆分" —— 用依赖白名单管住模块之间谁能调谁。
// 实验四：一个简易决策器 —— 输入场景，输出"该优化"还是"该拆分"。
//
// 全程只用 JDK 自带类，Java 17 可直接编译运行。

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println(" 单体项目 QPS 到 1 万，要不要拆微服务？");
        System.out.println("==================================================");

        experimentOne_singleAppOptimization();
        experimentTwo_costOfSplitting();
        experimentThree_logicalSplitFirst();
        experimentFour_decisionHelper();

        System.out.println();
        System.out.println("【总结】性能瓶颈优先用缓存、异步、读写分离解决；架构腐化（团队协作打架）才用微服务解耦。");
    }

    // =================================================================
    // 实验一：单体不动结构，光靠"缓存 + 异步化"能提多少吞吐？
    // =================================================================

    /** 一次请求里，查一次数据库要多久（毫秒）。真实项目里就是一条没走好索引的 SQL。 */
    private static final int DATABASE_QUERY_MILLIS = 20;
    /** 查缓存要多久。缓存就像记在手边的小本本，比翻数据库大账本快得多。 */
    private static final int CACHE_HIT_MILLIS = 1;
    /** 下单后要发短信通知，这活儿很慢，但用户其实并不需要等它。 */
    private static final int SEND_SMS_MILLIS = 30;

    /** 一共模拟多少次请求。 */
    private static final int REQUEST_COUNT = 600;
    /** 同时有多少个工作线程在干活（相当于 Tomcat 的线程池 / 数据库连接池大小）。 */
    private static final int WORKER_THREADS = 50;
    /** 用户只会反复看这几款热门商品，所以缓存命中率天然就很高。 */
    private static final int HOT_PRODUCT_KINDS = 10;

    private static void experimentOne_singleAppOptimization() throws Exception {
        printTitle("实验一：单体不拆，光加缓存和异步化，吞吐能提多少？");

        long nakedMillis = runRequests(false, false);
        long cachedMillis = runRequests(true, false);
        long cachedAndAsyncMillis = runRequests(true, true);

        System.out.printf("  ① 裸奔（每次都查库 + 同步发短信）    耗时 %4d ms，吞吐约 %6.0f QPS%n",
                nakedMillis, toQps(nakedMillis));
        System.out.printf("  ② 加缓存（热点数据只查一次库）        耗时 %4d ms，吞吐约 %6.0f QPS%n",
                cachedMillis, toQps(cachedMillis));
        System.out.printf("  ③ 缓存 + 异步化（发短信丢后台队列）   耗时 %4d ms，吞吐约 %6.0f QPS%n",
                cachedAndAsyncMillis, toQps(cachedAndAsyncMillis));

        double improveRatio = (double) nakedMillis / Math.max(1, cachedAndAsyncMillis);
        System.out.printf("%n  结论：一行业务代码都没拆，吞吐提升约 %.1f 倍。%n", improveRatio);
        System.out.println("        而且单体照样能水平扩容——同一份代码部署 10 台机器，前面挂负载均衡即可。");
        System.out.println("        所以「扛流量」这件事，加机器 + 缓存就够了，跟拆不拆微服务没关系。");
    }

    /**
     * 跑一轮请求，返回总耗时（毫秒）。
     *
     * @param useCache 是否启用缓存
     * @param useAsync 是否把"发短信"这种慢活儿丢到后台异步做
     */
    private static long runRequests(boolean useCache, boolean useAsync) throws Exception {
        // 缓存：key 是商品 id，value 是商品详情。ConcurrentHashMap 是"多个人同时读写也不会乱的 Map"。
        Map<Integer, String> cache = new ConcurrentHashMap<>();

        ExecutorService workerPool = Executors.newFixedThreadPool(WORKER_THREADS);
        // 后台短信线程池：用户不用等它，它慢慢发就行。
        ExecutorService smsPool = Executors.newFixedThreadPool(4);
        AtomicInteger sentSmsCount = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(REQUEST_COUNT);

        long startNanos = System.nanoTime();
        for (int i = 0; i < REQUEST_COUNT; i++) {
            int productId = i % HOT_PRODUCT_KINDS;
            workerPool.submit(() -> {
                try {
                    // 第一步：拿商品详情
                    if (useCache) {
                        // computeIfAbsent：小本本上有就直接念，没有才去翻数据库大账本，翻完顺手记上。
                        cache.computeIfAbsent(productId, Demo::queryProductFromDatabase);
                        sleepQuietly(CACHE_HIT_MILLIS);
                    } else {
                        queryProductFromDatabase(productId);
                    }

                    // 第二步：发短信通知
                    if (useAsync) {
                        // 异步化 = 先给用户说"收到了"，短信丢给后台慢慢发。
                        smsPool.submit(() -> {
                            sleepQuietly(SEND_SMS_MILLIS);
                            sentSmsCount.incrementAndGet();
                        });
                    } else {
                        sleepQuietly(SEND_SMS_MILLIS);
                        sentSmsCount.incrementAndGet();
                    }
                } finally {
                    allDone.countDown();
                }
            });
        }

        allDone.await();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        workerPool.shutdown();
        workerPool.awaitTermination(30, TimeUnit.SECONDS);
        // 后台短信可能还没发完——这正是异步化的意义：用户早就拿到响应了，剩下的慢慢补。
        smsPool.shutdownNow();
        return elapsedMillis;
    }

    /** 模拟一次数据库查询：慢，而且是所有性能问题的老家。 */
    private static String queryProductFromDatabase(int productId) {
        sleepQuietly(DATABASE_QUERY_MILLIS);
        return "商品-" + productId;
    }

    private static double toQps(long elapsedMillis) {
        return REQUEST_COUNT * 1000.0 / Math.max(1, elapsedMillis);
    }

    // =================================================================
    // 实验二：真拆成微服务，要多付哪两笔账？
    // =================================================================

    private static void experimentTwo_costOfSplitting() {
        printTitle("实验二：拆成微服务后，耗时和成功率会怎么变？");

        int simulateCount = 20000;
        // 一次下单要经过的 5 个环节。单体里是 5 次方法调用，微服务里是 5 次跨网络打电话。
        int hopCount = 5;
        // 每个服务自己的可用性 99.9%（一千次里失败一次）。
        double singleServiceAvailability = 0.999;

        System.out.println("  调用链：网关 → 订单 → 用户 → 库存 → 优惠券 → 支付（共 " + hopCount + " 跳）");
        System.out.println("  模拟 " + simulateCount + " 次下单请求，单个服务可用性按 99.9% 计算。");
        System.out.println();

        runAndPrintChainComparison(simulateCount, hopCount, singleServiceAvailability);
    }

    /** 真正做统计并打印对比表。 */
    private static void runAndPrintChainComparison(int simulateCount, int hopCount, double availability) {
        Random random = new Random(42); // 固定随机种子，保证每次运行结果一致，方便对照

        double monolithTotalLatency = 0;
        int monolithSuccess = 0;
        double microTotalLatency = 0;
        int microSuccess = 0;

        for (int i = 0; i < simulateCount; i++) {
            // ---- 单体：5 个环节都在一个进程里，方法调用几乎不耗时，也不会"网络失败" ----
            double monolithLatency = 2.0;                 // 纯业务计算 2ms
            monolithLatency += hopCount * 0.001;          // 进程内调用约 1 微秒/次，可忽略
            boolean monolithOk = random.nextDouble() < availability; // 只有业务本身可能出错
            monolithTotalLatency += monolithLatency;
            if (monolithOk) {
                monolithSuccess++;
            }

            // ---- 微服务：每一跳都要序列化 + 走网线，而且每一跳都可能挂 ----
            double microLatency = 2.0;                    // 同样的业务计算
            boolean microOk = true;
            for (int hop = 0; hop < hopCount; hop++) {
                microLatency += 3.0;                      // 网络往返 3ms
                microLatency += 0.5;                      // 参数序列化/反序列化 0.5ms
                if (random.nextDouble() >= availability) {
                    microOk = false;                      // 只要有一跳挂了，整条链就失败
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

        System.out.println();
        System.out.println("  两笔账：");
        System.out.println("   ① 耗时账：每跳多 3.5ms，5 跳就白白多出 17.5ms，还没算重试和排队。");
        System.out.printf("   ② 可用性账：0.999 的 %d 次方 ≈ %.4f，故障率直接放大 %d 倍。%n",
                hopCount, Math.pow(availability, hopCount), hopCount);
        System.out.println("   ③ 还有隐形账：链路追踪(SkyWalking)、配置中心(Nacos)、熔断限流(Sentinel)、");
        System.out.println("      分布式事务——每一样都要人维护。拆分不是免费的。");
    }

    // =================================================================
    // 实验三：真要拆，第一步是"逻辑拆分"——先在一个进程里把边界立起来
    // =================================================================

    /**
     * 模块注册表：声明"哪个业务模块允许调用哪些模块"。
     * 生活比喻：先在大屋子里砌隔断墙，走门要刷卡。刷卡失败就说明依赖关系画错了，
     * 趁早改，将来物理拆成独立服务时才不会一团乱。
     */
    private static class ModuleRegistry {
        private final Map<String, Set<String>> allowedDependencies = new LinkedHashMap<>();

        /** 声明一个模块，以及它被允许调用的下游模块。 */
        void declareModule(String moduleName, String... canCallModules) {
            allowedDependencies.put(moduleName, new LinkedHashSet<>(List.of(canCallModules)));
        }

        /** 模拟一次跨模块调用。不在白名单里就直接拦下来。 */
        void callModule(String fromModule, String toModule, String action) {
            Set<String> allowed = allowedDependencies.getOrDefault(fromModule, Set.of());
            if (!allowed.contains(toModule)) {
                throw new IllegalStateException(
                        "非法依赖：[" + fromModule + "] 不允许调用 [" + toModule + "]，会造成双向耦合，将来拆不开");
            }
            System.out.printf("     ✅ %s → %s：%s%n", fromModule, toModule, action);
        }
    }

    private static void experimentThree_logicalSplitFirst() {
        printTitle("实验三：拆之前先做逻辑拆分（模块化单体）");

        ModuleRegistry registry = new ModuleRegistry();
        // 依赖方向必须是单向的：订单可以问用户和库存，但用户和库存不许反过来找订单。
        registry.declareModule("order", "user", "inventory");
        registry.declareModule("user");        // 用户域是底层，不依赖任何人
        registry.declareModule("inventory");   // 库存域同理

        System.out.println("  依赖白名单：order → {user, inventory}；user、inventory 不依赖任何模块");
        System.out.println();

        System.out.println("  合法调用：");
        registry.callModule("order", "user", "查询下单人的收货地址");
        registry.callModule("order", "inventory", "扣减库存");

        System.out.println();
        System.out.println("  非法调用（新同事随手写了一行反向依赖）：");
        try {
            registry.callModule("user", "order", "在用户详情页直接查订单列表");
        } catch (IllegalStateException e) {
            System.out.println("     ❌ 被拦下：" + e.getMessage());
            System.out.println("        正确做法：用户域发个「用户已注销」事件，订单域自己去订阅，而不是反向调用。");
        }

        System.out.println();
        System.out.println("  为什么这一步值钱：依赖关系在一个进程里就理干净了，将来把包搬出去改成 RPC 调用，");
        System.out.println("  业务代码几乎不用重写。隔断墙都没砌就直接搬家，只会搬得鸡飞狗跳。");
    }

    // =================================================================
    // 实验四：一个简易决策器
    // =================================================================

    /**
     * 一个待判断的场景。
     *
     * @param name                   场景名字
     * @param qps                    每秒请求数
     * @param bottleneck             瓶颈在哪："数据库" / "团队协作" / "热点数据"
     * @param teamSize               开发团队人数
     * @param releaseConflictsPerWeek 每周因为代码冲突/联调导致的发版阻塞次数
     */
    private record Scenario(String name, int qps, String bottleneck, int teamSize, int releaseConflictsPerWeek) {
    }

    private static void experimentFour_decisionHelper() {
        printTitle("实验四：到底该优化还是该拆？");

        List<Scenario> scenarios = List.of(
                new Scenario("电商详情页，1 万 QPS 全是读，DB CPU 打满", 10000, "数据库", 8, 0),
                new Scenario("秒杀活动，1 万 QPS 砸在同一个商品上", 10000, "热点数据", 12, 1),
                new Scenario("QPS 只有 2000，但 40 人团队天天抢着发版", 2000, "团队协作", 40, 8),
                new Scenario("QPS 1 万且团队 35 人，发版经常互相等", 10000, "团队协作", 35, 6)
        );

        for (Scenario scenario : scenarios) {
            System.out.println("  场景：" + scenario.name());
            System.out.println("    建议：" + advise(scenario));
            System.out.println();
        }
    }

    /** 决策规则：痛的是机器就优化，痛的是人才拆分。 */
    private static String advise(Scenario scenario) {
        if ("热点数据".equals(scenario.bottleneck())) {
            return "别拆！做本地缓存(Caffeine) + 热点探测 + 分布式锁防超卖。拆微服务在这里只会多几跳网络。";
        }
        if ("数据库".equals(scenario.bottleneck())) {
            return "先优化单体：加 Redis 缓存 + 读写分离 + 慢 SQL 治理 + 水平扩容。等这些都榨干了再谈拆分。";
        }
        // 剩下就是协作问题了，再看团队规模和冲突频率
        if (scenario.teamSize() >= 30 && scenario.releaseConflictsPerWeek() >= 5) {
            return "该拆了，但第一步做「逻辑拆分」：按业务域分包 + 依赖白名单，边界稳定后再物理拆成独立服务。";
        }
        return "还不到时候。先做模块化单体，观察一到两个季度：协作痛点是否持续、边界是否清晰。";
    }

    // =================================================================
    // 小工具
    // =================================================================

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("--------------------------------------------------");
        System.out.println(" " + title);
        System.out.println("--------------------------------------------------");
    }

    /** 睡一会儿，用来模拟"这一步很慢"。被打断时安静退出，不刷屏。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留中断标记，是个好习惯
        }
    }
}
