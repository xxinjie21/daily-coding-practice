// 编译运行：javac --release 17 Demo.java && java Demo
//
// 题目：设计数据库表时，关联表和在一个表中加冗余字段关联各有什么优势？
// 本 Demo 严格按原题给出的思路实现：
//   1) 关联表（归一化 / 外键）：用户-角色用 user_role 关联表，改一处全局生效；
//   2) 冗余字段（反范式 / 快照）：订单表直接冗余商品名、收货地址，免 JOIN 直出；
//   3) 冗余字段一致性：源数据变更后，通过 MQ（这里用内存 EventBus 模拟）异步刷新副本。

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class Demo {

    // ---------- 1. 归一化（关联表）模型 ----------
    record Role(long id, String name) {}
    record User(long id, String name) {}

    // 关联表：用户 <-> 角色，通过外键关联，数据完全正范式
    record UserRole(long userId, long roleId) {}

    // ---------- 2. 反范式（冗余字段）模型 ----------
    record Product(long id, String name) {}

    // 订单表冗余商品名称、收货地址快照——下单即定格，免连表即可展示
    record Order(long id, long userId, long productId,
                 String productNameSnapshot, String addressSnapshot) {}

    // ---------- 3. 模拟 MQ：商品名称变更事件 ----------
    record ProductNameChangedEvent(long productId, String newName) {}

    // 简单内存事件派发器，模拟 MQ 异步刷冗余字段（解耦源写入与冗余同步）
    static class EventBus {
        private final ExecutorService executor =
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "mq-consumer");
                    t.setDaemon(true);
                    return t;
                });
        private final List<Consumer<ProductNameChangedEvent>> listeners = new ArrayList<>();

        void subscribe(Consumer<ProductNameChangedEvent> c) { listeners.add(c); }

        void publish(ProductNameChangedEvent e) {
            // 异步投递，模拟消息队列解耦，源写入不阻塞
            executor.submit(() -> listeners.forEach(l -> l.accept(e)));
        }

        void shutdown() { executor.shutdown(); }
    }

    // 仓储（用内存集合模拟数据库表）
    static class Repository {
        final Map<Long, Role> roles = new ConcurrentHashMap<>();
        final Map<Long, User> users = new ConcurrentHashMap<>();
        final List<UserRole> userRoles = Collections.synchronizedList(new ArrayList<>());
        final Map<Long, Product> products = new ConcurrentHashMap<>();
        final List<Order> orders = Collections.synchronizedList(new ArrayList<>());
    }

    public static void main(String[] args) throws Exception {
        Repository repo = new Repository();
        EventBus bus = new EventBus();

        // 初始化数据
        repo.roles.put(1L, new Role(1L, "管理员"));
        repo.roles.put(2L, new Role(2L, "普通用户"));
        repo.users.put(10L, new User(10L, "张三"));
        repo.userRoles.add(new UserRole(10L, 1L));
        repo.userRoles.add(new UserRole(10L, 2L));

        repo.products.put(100L, new Product(100L, "机械键盘"));
        repo.orders.add(new Order(1001L, 10L, 100L, "机械键盘", "北京市朝阳区xx路"));

        // 订阅：异步刷新订单里的冗余商品名称（模拟 binlog / MQ 消费）
        bus.subscribe(evt -> {
            synchronized (repo.orders) {
                for (int i = 0; i < repo.orders.size(); i++) {
                    Order o = repo.orders.get(i);
                    if (o.productId() == evt.productId()) {
                        repo.orders.set(i, new Order(o.id(), o.userId(), o.productId(),
                                evt.newName(), o.addressSnapshot()));
                    }
                }
            }
            System.out.println("[MQ消费] 异步刷新冗余字段：productId=" + evt.productId()
                    + " -> " + evt.newName());
        });

        // ===== 方式一：关联表（归一化）=====
        System.out.println("===== 关联表（归一化）查询用户角色：需 JOIN =====");
        List<String> roleNames = joinUserRoles(repo, 10L);
        System.out.println("用户[张三] 的角色：" + roleNames);

        // ===== 方式二：冗余字段（反范式）=====
        System.out.println("\n===== 冗余字段（反范式）查询订单：免 JOIN =====");
        Order o = repo.orders.get(0);
        System.out.println("订单 " + o.id() + " 商品名：" + o.productNameSnapshot()
                + "，收货地址：" + o.addressSnapshot());

        // ===== 商品改名，触发冗余字段一致性维护 =====
        System.out.println("\n===== 商品改名，发布变更事件 =====");
        repo.products.put(100L, new Product(100L, "客制化机械键盘"));
        bus.publish(new ProductNameChangedEvent(100L, "客制化机械键盘"));

        // 等待异步消费完成
        Thread.sleep(500);

        System.out.println("\n===== 改名后再次查询订单（冗余字段已异步一致）=====");
        Order o2 = repo.orders.get(0);
        System.out.println("订单 " + o2.id() + " 商品名：" + o2.productNameSnapshot());

        bus.shutdown();
    }

    // 模拟 SQL：SELECT r.name FROM user_role ur JOIN role r ON ur.role_id=r.id WHERE ur.user_id=?
    static List<String> joinUserRoles(Repository repo, long userId) {
        List<String> result = new ArrayList<>();
        for (UserRole ur : repo.userRoles) {
            if (ur.userId() == userId) {
                Role r = repo.roles.get(ur.roleId());
                if (r != null) result.add(r.name());
            }
        }
        return result;
    }
}
