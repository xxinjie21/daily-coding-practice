// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 这个小程序演示：在 Java 里，不写任何锁（synchronized / Lock）也能写出线程安全的单例。
 *
 * 单例 = 一个类在程序运行期间「只能有一个对象」。
 *   生活比喻：公司里唯一的公章，不能人人刻一个；多线程同时来领，也不该领出两个。
 *
 * 演示四个版本（都对应题解里讲的方法）：
 *   1) 静态内部类 Holder —— 懒加载、JVM 保证只建一次（推荐，主演示）
 *   2) 枚举 Enum      —— Effective Java 推荐，最省事最安全
 *   3) 饿汉式          —— 类加载就建好，简单但可能浪费资源
 *   4) 双重检查锁 DCL  —— 配合 volatile 防止指令重排（也用到一次轻量锁）
 *
 * 最后用一个多线程实验证明：不加锁的几个版本，50 个线程拿到的依然是「同一个对象」。
 */
public class Demo {

    // ===== 1) 静态内部类（懒加载登记式）—— 最推荐 =====
    // 思路：外部类加载时，内部类 Holder 不会被加载；只有第一次调用 getInstance() 才触发 Holder 加载。
    // JVM 在加载一个类时会自动加一把内部锁，保证类初始化只执行一次——
    // 这把「锁」是 JVM 替我们加的，业务代码里一行锁都不用写。
    static class InnerClassSingleton {
        private InnerClassSingleton() {} // 私有构造：外面 new 不出来，只能走 getInstance()

        private static class Holder {
            // static final：类加载时一次性赋值，线程安全且只建一次
            static final InnerClassSingleton INSTANCE = new InnerClassSingleton();
        }

        static InnerClassSingleton getInstance() {
            return Holder.INSTANCE; // 第一次访问 Holder 才真正创建实例（懒加载）
        }
    }

    // ===== 2) 枚举单例（Effective Java 推荐）—— 最省事最安全 =====
    // 枚举的实例天生由 JVM 保证只创建一次，而且天然防反射、防反序列化破坏。
    enum EnumSingleton {
        INSTANCE;
        void doWork() { /* 想让单例做的事写在这里 */ }
    }

    // ===== 3) 饿汉式 =====
    // 类一加载就把实例 new 好，最简单；缺点是单例很重又用不到时会白占资源。
    static class EagerSingleton {
        private static final EagerSingleton INSTANCE = new EagerSingleton();
        private EagerSingleton() {}
        static EagerSingleton getInstance() { return INSTANCE; }
    }

    // ===== 4) 双重检查锁 DCL（用 volatile，不是锁整个方法）=====
    // 注意：这里没用 synchronized 包住整个方法，而是配合 volatile 只在必要时加一次轻量锁。
    static class DclSingleton {
        // volatile：禁止指令重排，避免「对象还没构造完就被别的线程读到」的问题
        private static volatile DclSingleton instance;
        private DclSingleton() {}

        static DclSingleton getInstance() {
            if (instance == null) {                 // 第一次检查：已经建好就直接返回，不用进锁
                synchronized (DclSingleton.class) {  // 只有第一次才进这个轻量锁
                    if (instance == null) {          // 第二次检查：防止两个线程同时通过第一次检查
                        instance = new DclSingleton();
                    }
                }
            }
            return instance;
        }
    }

    // 一个通用接口：用来「拿单例对象」，让实验方法能复用四种写法
    interface InstanceGetter {
        Object get();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== 单例对象长啥样 =====");
        System.out.println("静态内部类实例: " + InnerClassSingleton.getInstance());
        System.out.println("枚举实例:       " + EnumSingleton.INSTANCE);
        System.out.println("饿汉式实例:     " + EagerSingleton.getInstance());
        System.out.println("DCL 实例:       " + DclSingleton.getInstance());
        System.out.println("静态内部类再拿一次（应和上面是同一个）: " + InnerClassSingleton.getInstance());

        // ===== 多线程实验：证明不加锁也安全 =====
        // 想象 50 个人同时去领「唯一公章」，看看最后大家领到的到底是不是同一个。
        testThreadSafe("静态内部类", () -> InnerClassSingleton.getInstance());
        testThreadSafe("枚举",       () -> EnumSingleton.INSTANCE);
        testThreadSafe("饿汉式",     () -> EagerSingleton.getInstance());
        testThreadSafe("DCL",        () -> DclSingleton.getInstance());
    }

    // 起 50 个线程同时去拿单例，统计「拿到了几个不同的对象」。
    // 如果只有一个对象，说明线程安全；若多于一个，就翻车了。
    static void testThreadSafe(String name, InstanceGetter getter) throws Exception {
        final int threadCount = 50;
        CountDownLatch startGate = new CountDownLatch(1);            // 发令枪：让 50 个线程同一刻起跑
        CountDownLatch endGate = new CountDownLatch(threadCount);    // 等所有线程跑完
        ConcurrentHashMap<Object, Boolean> got = new ConcurrentHashMap<>(); // 记录拿到过哪些对象

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startGate.await();            // 等发令枪响
                    Object obj = getter.get();    // 同时去拿单例
                    got.put(obj, Boolean.TRUE);   // 把对象记下来（同一个对象会被合并成一条）
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }).start();
        }

        startGate.countDown();  // 发令：所有线程同时开跑
        endGate.await();        // 等全部跑完

        String result = (got.size() == 1)
                ? "OK：50 个线程都拿到同一个对象 ✅"
                : ("翻车：拿到了 " + got.size() + " 个不同对象 ❌");
        System.out.println("[" + name + "] " + result);
    }
}
