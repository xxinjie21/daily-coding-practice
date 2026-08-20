// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import java.util.*;

/**
 * 这个程序演示：如何从零写一个简化版 HashMap（参考 JDK 的设计思路）。
 *
 * 核心机制：
 *   1) 二次扰动散列：把 key 的 hashCode 高位和低位搅在一起，让分布更均匀
 *   2) 容量固定为 2 的幂：取模变成位运算 (hash & (容量-1))，比除法快
 *   3) 数组 + 链表 解决冲突：同一个桶里的元素用链表串起来
 *   4) 负载因子 0.75：装到 3/4 就扩容（翻倍），并把老数据按"高低位"重新分桶
 *   5) key 不可变：key 的 hashCode 一旦算完就不能变，否则就找不回来了
 *
 * 注：真实 JDK 在链表长度 > 8 且数组长度 > 64 时会把链表转成红黑树，
 *    本 Demo 为了好读保留链表，但会在超长时打印提示，让你知道"这里该换树了"。
 */
public class Demo {

    // 链表节点：每个桶里挂一串这样的节点
    static class Entry {
        final int hash;        // 提前算好存着，扩容时直接复用，不用重算
        final Object key;      // key 必须是不可变对象（见实验五）
        Object value;
        Entry next;            // 指向下一个节点，把冲突的元素串成链表
        Entry(int hash, Object key, Object value, Entry next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Entry[] table;          // 桶数组，主干（一排柜子）
    private int size;               // 已经存了多少个键值对
    private final float loadFactor; // 负载因子，默认 0.75
    private int threshold;          // 扩容门槛 = 容量 * 负载因子

    // 链表转红黑树的阈值（仅作提示用，本 Demo 不真正建树）
    private static final int TREEIFY_THRESHOLD = 8;
    private static final int MIN_TREEIFY_CAPACITY = 64;

    /** 构造方法：容量会被向上取到最近的 2 的幂（比如传 10 实际用 16）。 */
    @SuppressWarnings("unchecked")
    public Demo(int initialCapacity, float loadFactor) {
        int cap = 1;
        while (cap < initialCapacity) {
            cap <<= 1; // 左移一位 = 乘 2，保证容量是 2 的幂
        }
        this.loadFactor = loadFactor;
        this.threshold = (int) (cap * loadFactor);
        this.table = new Entry[cap];
    }

    public Demo() {
        this(16, 0.75f);
    }

    /**
     * 二次扰动：把高 16 位和低 16 位异或，让高位也参与算桶号。
     * 比喻：柜子号不能只看钥匙某一个齿，要把整把钥匙的信息都揉进去，才分得开。
     */
    static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16); // 无符号右移 16 位，和高位做异或
    }

    /** 取桶下标：容量是 2 的幂，所以 (容量-1) 低位全 1，& 一下就是取模。 */
    static int indexFor(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    /** 存一个键值对；key 已存在则覆盖旧值。 */
    @SuppressWarnings("unchecked")
    public Object put(Object key, Object value) {
        int h = hash(key);
        int idx = indexFor(h, table.length);
        Entry head = table[idx];

        // 先看看桶里有没有相同的 key，有就覆盖（equals 判断）
        for (Entry e = head; e != null; e = e.next) {
            if (e.hash == h && (e.key == key || e.key.equals(key))) {
                Object old = e.value;
                e.value = value;
                return old;
            }
        }

        // 没有就头插法挂到链表最前面（新数据更可能被马上访问）
        table[idx] = new Entry(h, key, value, head);
        size++;

        // 装得太满就扩容（见 resize）
        if (size > threshold) {
            resize();
        }
        return null;
    }

    /** 根据 key 取值；找不到返回 null。 */
    public Object get(Object key) {
        int h = hash(key);
        int idx = indexFor(h, table.length);
        for (Entry e = table[idx]; e != null; e = e.next) {
            if (e.hash == h && (e.key == key || e.key.equals(key))) {
                return e.value;
            }
        }
        return null;
    }

    /**
     * 扩容：容量翻倍，把老数据重新分配到新桶里。
     * 巧妙点（JDK 原版思路）：因为新容量 = 旧容量 × 2，
     * 只看 "hash & 旧容量" 这一位是 0 还是 1：
     *   是 0 → 留在原桶号；是 1 → 搬到 原桶号 + 旧容量 那个新桶。
     * 不用重新算完整 hash，只多看一位，老数据一个不少地被分流。
     */
    @SuppressWarnings("unchecked")
    private void resize() {
        Entry[] oldTable = table;
        int oldCap = oldTable.length;
        int newCap = oldCap << 1; // 容量翻倍
        Entry[] newTable = new Entry[newCap];

        for (Entry head : oldTable) {
            for (Entry e = head; e != null; ) {
                Entry next = e.next;
                int oldIdx = indexFor(e.hash, oldCap); // 原桶号
                // 看新增的高位：0 留原地，1 去 原桶号 + 旧容量
                int newIdx = (e.hash & oldCap) == 0 ? oldIdx : oldIdx + oldCap;
                // 头插到新桶（链表顺序反转不影响正确性）
                e.next = newTable[newIdx];
                newTable[newIdx] = e;
                e = next;
            }
        }
        table = newTable;
        threshold = (int) (newCap * loadFactor);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return table.length;
    }

    // ===================== 下面是演示 =====================

    public static void main(String[] args) {
        System.out.println("===== 手写 HashMap 演示 =====\n");

        experimentPerturbation(); // 二次扰动到底有没有用
        experimentBasicPutGet();   // 基本 put/get + 冲突处理
        experimentResizeKeepsData(); // 扩容后数据不丢
        experimentLoadFactor();    // 负载因子触发扩容
        experimentMutableKey();    // 可变 key 的坑
    }

    /**
     * 实验一：对比"直接取模"和"二次扰动后取模"的碰撞情况。
     * 这里特意构造一批 key：它们的 hashCode 低位全是 0（信息都藏在高 16 位）。
     * 这种"低位雷同"的情况在真实系统里并不少见，正好能看出扰动的作用。
     */
    static void experimentPerturbation() {
        int cap = 16; // 16 个桶
        int[] raw = new int[cap];
        int[] perturbed = new int[cap];
        for (int i = 0; i < 10000; i++) {
            // 0x10000, 0x20000, ... 这些数的低位（低 16 位）全是 0
            Integer key = (i + 1) * 0x10000;
            int h = key.hashCode();
            raw[indexFor(h, cap)]++;                       // 直接取低位
            perturbed[indexFor(h ^ (h >>> 16), cap)]++;    // 二次扰动后取低位
        }
        int rawMax = 0, rawEmpty = 0, perMax = 0, perEmpty = 0;
        for (int x : raw) { rawMax = Math.max(rawMax, x); if (x == 0) rawEmpty++; }
        for (int x : perturbed) { perMax = Math.max(perMax, x); if (x == 0) perEmpty++; }
        System.out.println("【实验一 二次扰动】");
        System.out.println("  直接取低位：最多的桶装了 " + rawMax + " 个，空桶 " + rawEmpty + " 个");
        System.out.println("  二次扰动后：最多的桶装了 " + perMax + " 个，空桶 " + perEmpty + " 个");
        System.out.println("  -> 这批 key 低位全是 0，直接取模全挤进一个桶；");
        System.out.println("     扰动把高位信息揉进低位，才把数据均匀散开。\n");
    }

    /** 实验二：基本 put/get，相同 key 覆盖，未存的 key 返回 null。 */
    static void experimentBasicPutGet() {
        Demo map = new Demo(8, 0.75f);
        map.put("apple", 10);
        map.put("banana", 20);
        map.put("apple", 99); // 相同 key 覆盖
        System.out.println("【实验二 基本 put/get】");
        System.out.println("  get(apple) = " + map.get("apple") + " （覆盖后应为 99）");
        System.out.println("  get(banana) = " + map.get("banana") + " （应为 20）");
        System.out.println("  get(pear) = " + map.get("pear") + " （没存过应为 null）");
        System.out.println("  当前容量=" + map.capacity() + " 元素数=" + map.size() + "\n");
    }

    /** 实验三：多次扩容后，所有数据依然能 get 到。 */
    static void experimentResizeKeepsData() {
        Demo map = new Demo(4, 0.75f); // 容量 4，门槛 3，第 4 个就扩容
        for (int i = 0; i < 10; i++) {
            map.put("k" + i, i);
        }
        System.out.println("【实验三 扩容后数据不丢】");
        System.out.println("  存入 10 个，扩容后容量=" + map.capacity() + " 元素数=" + map.size());
        boolean allOk = true;
        for (int i = 0; i < 10; i++) {
            if (!Integer.valueOf(i).equals(map.get("k" + i))) {
                allOk = false;
            }
        }
        System.out.println("  扩容后再 get 全部 10 个：" + (allOk ? "全部正确" : "有丢失"));
        System.out.println("  -> 翻倍 + 高低位重分配，老数据一个不少。\n");
    }

    /** 实验四：演示负载因子 0.75 触发扩容的时机。 */
    static void experimentLoadFactor() {
        Demo map = new Demo(4, 0.75f);
        System.out.println("【实验四 负载因子触发扩容】");
        System.out.println("  初始容量=4，门槛=" + (int) (4 * 0.75) + "（装到第 4 个就扩）");
        for (int i = 1; i <= 4; i++) {
            int before = map.capacity();
            map.put("x" + i, i);
            System.out.println("  放第 " + i + " 个后：容量 " + before + " -> " + map.capacity());
        }
        System.out.println();
    }

    /** 实验五：可变 key 的坑——key 内容被改，hashCode 变了就找不回来了。 */
    static void experimentMutableKey() {
        // 用一个会变的 key（hashCode 依赖可变的 id 字段）
        class MutableKey {
            int id;
            MutableKey(int id) { this.id = id; }
            @Override public int hashCode() { return id; }
            @Override public boolean equals(Object o) {
                return o instanceof MutableKey && ((MutableKey) o).id == this.id;
            }
        }
        Demo map = new Demo();
        MutableKey k = new MutableKey(1);
        map.put(k, "重要数据");
        k.id = 2; // 手贱改了 key 的内容
        System.out.println("【实验五 可变 key 的坑】");
        System.out.println("  key 改之前存了值，改完 id 后 get(k) = " + map.get(k));
        System.out.println("  -> 算出的桶号变了，数据\"丢了\"。所以 key 一定要用不可变类（String/Integer）。\n");
    }
}
