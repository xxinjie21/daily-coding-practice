// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   演示「不写任何锁，也能写出线程安全的单例」。单例 = 一个类在程序运行期间只能有一个对象，
//   好比公司里唯一的公章，不能人人刻一个；多线程同时来领，也不该领出两个。
//
//   四种写法：
//     ① 静态内部类 Holder —— 懒加载；JVM 加载类时自动加一把内部锁保证只初始化一次，业务代码一行锁都不用写
//     ② 枚举 Enum         —— Effective Java 推荐，最省事；JVM 保证只建一次，还天然防反射、防反序列化
//     ③ 双重检查锁 DCL     —— 配 volatile 防指令重排，只在第一次初始化时进一次轻量锁
//     ④ CAS 无锁           —— AtomicReference.compareAndSet，抢不到就丢掉自己造的重新来
//
//   最后起 50 个线程同时去领单例，统计「到底领到了几个不同的对象」——只有一个才算安全。
//
// 全程只用 JDK 自带类，不需要任何中间件。

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class Demo {

    /** ① 静态内部类：外部类加载时 Holder 不会跟着加载，第一次 getInstance() 才触发它 */
    static class HolderSingleton {
        private HolderSingleton() {}   // 私有构造：外面 new 不出来，只能走 getInstance()

        private static class Holder {
            static final HolderSingleton INSTANCE = new HolderSingleton();
        }

        static HolderSingleton getInstance() {
            return Holder.INSTANCE;
        }
    }

    /** ② 枚举：实例天生由 JVM 保证只创建一次 */
    enum EnumSingleton {
        INSTANCE
    }

    /** ③ 双重检查锁：volatile 禁止指令重排，避免「对象还没构造完就被别的线程读到」 */
    static class DclSingleton {
        private static volatile DclSingleton instance;

        private DclSingleton() {}

        static DclSingleton getInstance() {
            if (instance == null) {                    // 第一次检查：建好了就直接返回，不进锁
                synchronized (DclSingleton.class) {    // 只有第一次才进这把轻量锁
                    if (instance == null) {            // 第二次检查：防止两个线程同时通过第一次检查
                        instance = new DclSingleton();
                    }
                }
            }
            return instance;
        }
    }

    /** ④ CAS 无锁：抢不到就丢掉自己造的那个重新来，最终只有一个会被发布出去 */
    static class CasSingleton {
        private static final AtomicReference<CasSingleton> INSTANCE = new AtomicReference<>();

        private CasSingleton() {}

        static CasSingleton getInstance() {
            while (true) {
                CasSingleton current = INSTANCE.get();
                if (current != null) {
                    return current;
                }
                CasSingleton created = new CasSingleton();
                if (INSTANCE.compareAndSet(null, created)) {
                    return created;
                }
            }
        }
    }

    /** 一个「领单例」的动作，让实验方法能复用四种写法 */
    interface InstanceGetter {
        Object get();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== 单例对象长啥样 =====");
        System.out.println("静态内部类：" + HolderSingleton.getInstance());
        System.out.println("枚举：      " + EnumSingleton.INSTANCE);
        System.out.println("DCL：       " + DclSingleton.getInstance());
        System.out.println("CAS：       " + CasSingleton.getInstance());
        System.out.println("静态内部类再领一次（应和第一次是同一个）：" + HolderSingleton.getInstance());

        System.out.println("\n===== 50 个线程同时来领，看会不会领出多个 =====");
        testThreadSafe("静态内部类", HolderSingleton::getInstance);
        testThreadSafe("枚举", () -> EnumSingleton.INSTANCE);
        testThreadSafe("DCL", DclSingleton::getInstance);
        testThreadSafe("CAS", CasSingleton::getInstance);
    }

    /** 起 50 个线程同时去拿单例，统计拿到了几个不同的对象：只有一个才算线程安全 */
    static void testThreadSafe(String name, InstanceGetter getter) throws Exception {
        int threadCount = 50;
        CountDownLatch startGate = new CountDownLatch(1);              // 发令枪：让 50 个线程同一刻起跑
        CountDownLatch endGate = new CountDownLatch(threadCount);
        ConcurrentHashMap<Object, Boolean> got = new ConcurrentHashMap<>();   // 同一个对象会被合并成一条

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    got.put(getter.get(), Boolean.TRUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }).start();
        }
        startGate.countDown();
        endGate.await();

        System.out.println("[" + name + "] " + (got.size() == 1
                ? "OK：50 个线程都拿到同一个对象"
                : "翻车：拿到了 " + got.size() + " 个不同对象"));
    }
}
