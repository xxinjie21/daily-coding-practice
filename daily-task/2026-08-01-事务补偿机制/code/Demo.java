// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo

import java.util.ArrayList;
import java.util.List;

/**
 * 这个程序演示「分布式事务补偿机制」的核心思路。
 *
 * 场景：买东西要按顺序做三件事——①创建订单 ②扣库存 ③扣余额。
 * 这三件事分属不同系统，没法用数据库的回滚一起撤销（跨系统回滚不了）。
 * 一旦中途失败，就用「补偿」把前面已经成功的事反向补平。
 *
 * 演示四件事：
 *   1) 每步都记「状态」，补偿前先查状态（保证幂等，不重复扣款）
 *   2) 补偿失败会重试，最多 3 次，还失败就进「人工干预队列」
 *   3) 补偿链控制在 5 步以内（这里演示 3 步）
 *   4) 最后跑一次「对账」，检查订单和余额是否一致
 *
 * 说明：真实环境里补偿是用消息队列「异步」重试的（如 RocketMQ 事务消息），
 * 这里为了好读，用同步循环代替，机制是一样的。
 */
public class Demo {

    // ===== 1. 状态机：每一步处在哪个阶段（对应原题的"事务日志表"）=====
    enum Status {
        PENDING,        // 还没做
        DONE,           // 已成功
        COMPENSATED,    // 已补偿（反向操作成功）
        FAILED          // 补偿失败、待人工处理
    }

    // ===== 2. 一个业务步骤：有"正常做"和"反向补偿"两件事 =====
    static class Step {
        final String name;
        final Runnable action;       // 正常操作
        final Runnable compensate;   // 反向补偿
        Status status = Status.PENDING;

        Step(String name, Runnable action, Runnable compensate) {
            this.name = name;
            this.action = action;
            this.compensate = compensate;
        }
    }

    // ===== 3. 人工干预队列：补偿 3 次都失败的单子丢这里等运维处理 =====
    static class ManualQueue {
        static final List<String> queue = new ArrayList<>();
        static void offer(Step s) { queue.add(s.name); }
        static int size() { return queue.size(); }
    }

    // ===== 4. 事务协调器：负责"正向执行"和"反向补偿" =====
    static class Coordinator {
        final String globalTxId;             // 全局事务ID
        final List<Step> steps = new ArrayList<>();

        Coordinator(String globalTxId) { this.globalTxId = globalTxId; }
        void add(Step s) { steps.add(s); }

        // 正向执行：依次做；某一步抛异常就停，由调用方触发补偿
        void execute() {
            for (Step s : steps) {
                s.action.run();
                s.status = Status.DONE;     // 记账：这步成功了
                System.out.println("  [做] " + s.name + " 成功（状态=DONE）");
            }
        }

        // 反向补偿：从最后一步往前补（先发生的后撤销），且先查状态
        void compensate() {
            System.out.println("  >> 开始补偿（全局事务 " + globalTxId + "）");
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step s = steps.get(i);
                // 幂等保护：只有"已成功"的步才需要撤销，没成功的直接跳过
                if (s.status != Status.DONE) {
                    System.out.println("  [跳过] " + s.name + " 状态=" + s.status + "，无需补偿");
                    continue;
                }
                boolean ok = retryCompensate(s);
                s.status = ok ? Status.COMPENSATED : Status.FAILED;
            }
        }

        // 补偿动作：最多重试 3 次，每次代表真实场景里的一条延迟消息；全失败进人工队列
        boolean retryCompensate(Step s) {
            final int MAX = 3;
            for (int attempt = 1; attempt <= MAX; attempt++) {
                try {
                    s.compensate.run();
                    System.out.println("  [补偿] " + s.name + " 第" + attempt + "次成功");
                    return true;
                } catch (Exception e) {
                    System.out.println("  [补偿] " + s.name + " 第" + attempt + "次失败：" + e.getMessage());
                    if (attempt < MAX) {
                        System.out.println("         → 发延迟消息，稍后重试（真实场景用 MQ 延迟队列）");
                    }
                }
            }
            System.out.println("  [人工] " + s.name + " 补偿 3 次都失败，转入人工干预队列");
            ManualQueue.offer(s);
            return false;
        }
    }

    // ===== 5. 三个"业务系统"（用简单字段模拟数据库状态）=====
    static class OrderService {
        boolean orderCreated = false;
        void create() { orderCreated = true; }      // 下单
        void cancel() { orderCreated = false; }      // 撤单（补偿）
    }
    static class StockService {
        int stock = 100;
        void deduct() { stock -= 1; }                // 扣 1 件库存
        void refund() { stock += 1; }                // 退回库存（补偿）
    }
    static class AccountService {
        int balance = 50;
        void deduct() {                              // 扣 10 元；钱不够就抛异常
            if (balance < 10) throw new RuntimeException("余额不足");
            balance -= 10;
        }
        void refund() { balance += 10; }             // 退 10 元（补偿）
    }

    // 一个"会偶尔失败"的补偿动作：前 failTimes 次失败，之后才成功（演示重试/人工队列）
    static Runnable flaky(Runnable real, int failTimes, String label) {
        return new Runnable() {
            int n = 0;
            public void run() {
                if (n < failTimes) { n++; throw new RuntimeException(label + " 模拟故障#" + n); }
                real.run();
            }
        };
    }

    // 一个"永远失败"的正向动作：模拟"扣款服务调用失败"，且不改变余额（演示正向失败触发补偿）
    static Runnable alwaysFail(String msg) {
        return () -> { throw new RuntimeException(msg); };
    }

    // 对账：订单和余额应该对得上，否则就是异常单
    static void reconcile(String txId, boolean orderCreated, int stock, int balance) {
        boolean consistent = orderCreated
                ? (stock == 99 && balance == 40)    // 下单成功：库存 99、余额 40
                : (stock == 100 && balance == 50);  // 下单失败：都恢复成原样
        System.out.println("  [对账] " + txId + (consistent ? " 一致 ✅" : " 不一致 ❌，需人工修复"));
    }

    public static void main(String[] args) {
        System.out.println("==== 演示：分布式事务补偿机制 ====");

        scenarioA_AllSuccess();
        scenarioB_CompensateOnce();
        scenarioC_RetryThenSuccess();
        scenarioD_ManualQueue();
    }

    // 场景A：三步全成功，无需补偿
    static void scenarioA_AllSuccess() {
        System.out.println("\n--- 场景A：三步全成功，无需补偿 ---");
        OrderService order = new OrderService();
        StockService stock = new StockService();
        AccountService acct = new AccountService();
        Coordinator tx = new Coordinator("tx-A");
        tx.add(new Step("创建订单", order::create, order::cancel));
        tx.add(new Step("扣库存",   stock::deduct, stock::refund));
        tx.add(new Step("扣余额",   acct::deduct,  acct::refund));

        tx.execute();   // 余额 50 够扣，不会抛异常
        System.out.println("  ✅ 三步全部成功，无需补偿");
        reconcile("tx-A", order.orderCreated, stock.stock, acct.balance);
    }

    // 场景B：扣余额失败 → 触发补偿，一次成功
    static void scenarioB_CompensateOnce() {
        System.out.println("\n--- 场景B：扣余额失败 → 触发补偿，一次成功 ---");
        OrderService order = new OrderService();
        StockService stock = new StockService();
        AccountService acct = new AccountService();   // 余额 50，足够扣
        Coordinator tx = new Coordinator("tx-B");
        tx.add(new Step("创建订单", order::create, order::cancel));
        tx.add(new Step("扣库存",   stock::deduct, stock::refund));
        // 扣余额的正向动作直接失败（网关超时），且不改余额 → 触发补偿，余额保持 50
        tx.add(new Step("扣余额",   alwaysFail("扣款服务调用失败（网关超时）"), acct::refund));

        try {
            tx.execute();
        } catch (Exception e) {
            System.out.println("  ❌ 正向执行出错：" + e.getMessage() + " → 开始反向补偿");
            tx.compensate();
        }
        System.out.println("  结果：订单=" + order.orderCreated + " 库存=" + stock.stock + " 余额=" + acct.balance);
        reconcile("tx-B", order.orderCreated, stock.stock, acct.balance);
    }

    // 场景C：补偿本身也失败 → 重试 2 次后成功
    static void scenarioC_RetryThenSuccess() {
        System.out.println("\n--- 场景C：补偿本身也失败 → 重试 2 次后成功 ---");
        OrderService order = new OrderService();
        StockService stock = new StockService();
        AccountService acct = new AccountService();
        Coordinator tx = new Coordinator("tx-C");
        tx.add(new Step("创建订单", order::create, order::cancel));
        // 回库存的补偿：前 2 次失败，第 3 次成功（演示重试机制）
        tx.add(new Step("扣库存", stock::deduct, flaky(stock::refund, 2, "回库存补偿")));
        // 扣余额正向失败，触发补偿
        tx.add(new Step("扣余额", alwaysFail("扣款服务调用失败（网关超时）"), acct::refund));

        try {
            tx.execute();
        } catch (Exception e) {
            System.out.println("  ❌ 正向执行出错：" + e.getMessage() + " → 开始反向补偿");
            tx.compensate();
        }
        System.out.println("  结果：订单=" + order.orderCreated + " 库存=" + stock.stock + " 余额=" + acct.balance);
        reconcile("tx-C", order.orderCreated, stock.stock, acct.balance);
    }

    // 场景D：补偿彻底失败 → 进人工干预队列
    static void scenarioD_ManualQueue() {
        System.out.println("\n--- 场景D：补偿彻底失败 → 进人工干预队列 ---");
        OrderService order = new OrderService();
        StockService stock = new StockService();
        AccountService acct = new AccountService();
        Coordinator tx = new Coordinator("tx-D");
        tx.add(new Step("创建订单", order::create, order::cancel));
        // 回库存补偿永久失败（99 次都失败）→ 3 次后转入人工队列
        tx.add(new Step("扣库存", stock::deduct, flaky(stock::refund, 99, "回库存补偿")));
        // 扣余额正向失败，触发补偿
        tx.add(new Step("扣余额", alwaysFail("扣款服务调用失败（网关超时）"), acct::refund));

        try {
            tx.execute();
        } catch (Exception e) {
            System.out.println("  ❌ 正向执行出错：" + e.getMessage() + " → 开始反向补偿");
            tx.compensate();
        }
        System.out.println("  结果：订单=" + order.orderCreated + " 库存=" + stock.stock + " 余额=" + acct.balance);
        reconcile("tx-D", order.orderCreated, stock.stock, acct.balance);
        System.out.println("  人工队列待处理数：" + ManualQueue.size());
    }
}
