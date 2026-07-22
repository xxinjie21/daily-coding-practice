// ============================================================
// 关联表 vs 冗余字段 Demo（Java 17）
// 编译运行：javac --release 17 Demo.java && java Demo
//
// 【这个程序在干嘛？】
// 设计数据库表时，两个表要"关联"起来有两种常见做法：
//
//   做法 A：关联表（外键 / 归一化）
//     用户表、角色表各存一份，再加一张 user_role 中间表把两边连起来。
//     好处：数据只存一处，改名只改一处，绝不会不一致。
//     代价：查询时要 JOIN 多张表。
//
//   做法 B：冗余字段（反范式 / 快照）
//     在订单表里直接把"商品名""收货地址"也抄一份存着。
//     好处：下单即定格，查订单不用 JOIN，直接就能展示。
//     代价：商品改名时，已下订单里的副本要跟着更新（一致性问题）。
//
// 本 Demo 两种都演示，并用一个"模拟消息队列"展示冗余字段如何异步保持一致。
// ============================================================

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class Demo {

    // ===== 做法 A 用到的表（归一化，数据只存一处）=====
    record Role(long id, String name) {}            // 角色表
    record User(long id, String name) {}            // 用户表
    record UserRole(long userId, long roleId) {}    // 中间关联表：把用户和角色连起来

    // ===== 做法 B 用到的表（冗余字段，订单里抄一份）=====
    record Product(long id, String name) {}         // 商品表（唯一权威来源）

    // 订单表：除了商品 id，还把商品名、收货地址"快照"一份存在订单里
    record Order(long id, long userId, long productId,
                 String productNameSnapshot, String addressSnapshot) {}

    // 商品改名事件（模拟 binlog / MQ 消息）
    record ProductRenamedEvent(long productId, String newName) {}

    // ----------------------------------------------------------
    // 模拟消息队列（MQ）：商品改名后，异步通知各订单更新冗余字段
    // ----------------------------------------------------------
    // 为什么用"队列 + 异步"？
    //   商品改名是"源数据"变更，订单里的冗余副本要跟上。
    //   在主流程里同步改所有订单会拖慢写入，所以用 MQ 解耦：
    //   源写入只发一条消息，订单更新交给后台消费者慢慢做。
    static class FakeMq {
        // 单线程后台消费者（设为守护线程，主程序结束它就结束）
        private final ExecutorService worker =
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "mq-consumer");
                    t.setDaemon(true);
                    return t;
                });

        // 订阅者列表：谁关心"商品改名"事件就登记在这里
        private final List<Consumer<ProductRenamedEvent>> subscribers = new ArrayList<>();

        // 登记一个订阅者（这里就是"更新订单冗余字段"的逻辑）
        void subscribe(Consumer<ProductRenamedEvent> handler) {
            subscribers.add(handler);
        }

        // 发布一条消息：丢给后台线程处理，不阻塞主流程
        void publish(ProductRenamedEvent event) {
            worker.submit(() -> subscribers.forEach(h -> h.accept(event)));
        }

        void close() { worker.shutdown(); }
    }

    // 用内存 Map/List 假装这些是数据库表
    static class Database {
        final Map<Long, Role> roles = new ConcurrentHashMap<>();
        final Map<Long, User> users = new ConcurrentHashMap<>();
        final List<UserRole> userRoles = Collections.synchronizedList(new ArrayList<>());
        final Map<Long, Product> products = new ConcurrentHashMap<>();
        final List<Order> orders = Collections.synchronizedList(new ArrayList<>());
    }

    public static void main(String[] args) throws Exception {
        Database db = new Database();
        FakeMq mq = new FakeMq();

        // ---- 准备一些基础数据 ----
        db.roles.put(1L, new Role(1L, "管理员"));
        db.roles.put(2L, new Role(2L, "普通用户"));
        db.users.put(10L, new User(10L, "张三"));
        // 张三同时是管理员和普通用户 -> 通过中间表关联
        db.userRoles.add(new UserRole(10L, 1L));
        db.userRoles.add(new UserRole(10L, 2L));

        db.products.put(100L, new Product(100L, "机械键盘"));
        // 下单时把商品名、地址"快照"进订单（冗余字段）
        db.orders.add(new Order(1001L, 10L, 100L, "机械键盘", "北京市朝阳区xx路"));

        // ---- 订阅 MQ：商品改名时，异步刷新订单里的冗余商品名 ----
        mq.subscribe(event -> {
            synchronized (db.orders) {
                for (int i = 0; i < db.orders.size(); i++) {
                    Order o = db.orders.get(i);
                    if (o.productId() == event.productId()) {
                        // 用新名字生成一份新订单记录，替换旧的（其他字段保持不变）
                        db.orders.set(i, new Order(o.id(), o.userId(), o.productId(),
                                event.newName(), o.addressSnapshot()));
                    }
                }
            }
            System.out.println("[MQ后台] 异步更新冗余字段：商品" + event.productId()
                    + " 改名为 " + event.newName());
        });

        // ===== 做法 A：关联表查询（需要 JOIN）=====
        System.out.println("===== 做法A：关联表查询（JOIN user_role + role）=====");
        List<String> roles = findRolesByUser(db, 10L);
        System.out.println("用户[张三] 拥有的角色：" + roles);

        // ===== 做法 B：冗余字段查询（免 JOIN）=====
        System.out.println("\n===== 做法B：冗余字段查询（直接读订单里的快照）=====");
        Order order = db.orders.get(0);
        System.out.println("订单 " + order.id() + " 的商品名：" + order.productNameSnapshot()
                + "，收货地址：" + order.addressSnapshot());

        // ===== 商品改名，触发冗余字段一致性维护 =====
        System.out.println("\n===== 商品改名：源数据变了 =====");
        db.products.put(100L, new Product(100L, "客制化机械键盘"));
        mq.publish(new ProductRenamedEvent(100L, "客制化机械键盘"));

        Thread.sleep(500); // 等后台 MQ 消费完

        System.out.println("\n===== 改名后再次查订单（冗余字段已自动同步）=====");
        Order after = db.orders.get(0);
        System.out.println("订单 " + after.id() + " 的商品名：" + after.productNameSnapshot());

        mq.close();
    }

    // 模拟 SQL：
    //   SELECT r.name FROM user_role ur
    //   JOIN role r ON ur.role_id = r.id
    //   WHERE ur.user_id = ?
    // 因为是关联表，改名只改 role 表一行，这里查询结果自动是最新的。
    static List<String> findRolesByUser(Database db, long userId) {
        List<String> result = new ArrayList<>();
        for (UserRole link : db.userRoles) {
            if (link.userId() == userId) {
                Role role = db.roles.get(link.roleId());
                if (role != null) result.add(role.name());
            }
        }
        return result;
    }
}
