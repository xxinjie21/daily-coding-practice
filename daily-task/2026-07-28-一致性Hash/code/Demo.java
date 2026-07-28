// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================== 这个程序在干嘛 ==============================
// 演示"一致性 Hash 算法"，并和"普通 Hash 取模"做对比实验，验证原题的三个结论：
//   实验一：普通 Hash 取模，3 台机器扩到 4 台，看有多少数据"找不到家"（预期约 75% 错位）
//   实验二：一致性 Hash，同样 3 台扩到 4 台，看错位比例（预期只有约 25%，即新节点该接管的那部分）
//   实验三：虚拟节点少 vs 多，看数据在 3 台机器上分得均不均匀（治"数据倾斜"）
//
// 核心思路（按原题文档）：
//   1) 把 0 ~ 2^32-1 想成一个首尾相接的"钟表盘"（Hash 环）
//   2) 服务器按 "IP:端口" 哈希后钉在环上；数据 key 哈希后也落在环上
//   3) 数据顺时针走，遇到的第一台服务器就是它的归宿
//   4) 每台物理机分身出多个"虚拟节点"撒在环上，让分布更均匀
// ==========================================================================

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class Demo {

    /**
     * 一致性 Hash 路由器：整个算法的核心组件。
     * 你可以把它想象成"快递分拣台"：包裹（key）进来，它告诉你该送哪个网点（节点）。
     */
    static class ConsistentHashRouter {

        // Hash 环本体：TreeMap 是一个"自动按数字排好序的字典"，
        // 它能高效回答"大于等于 X 的第一个位置上钉的是谁"——正好就是"顺时针找下一个节点"。
        // key = 环上的位置（哈希值），value = 虚拟节点对应的物理节点名
        private final TreeMap<Long, String> hashRing = new TreeMap<>();

        // 每台物理机分身出几个虚拟节点。分身越多，分布越均匀（工业界常用 100~200 个）
        private final int virtualNodeCount;

        ConsistentHashRouter(int virtualNodeCount) {
            this.virtualNodeCount = virtualNodeCount;
        }

        /**
         * 物理节点上环：生成 N 个分身（虚拟节点），逐个哈希后钉到环上。
         * 好比一个人往抽奖箱里放 N 张写着自己名字的券，券越多，被抽中的比例越接近应得份额。
         */
        void addNode(String nodeName) {
            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualNodeName = nodeName + "#VN" + i; // 分身名：如 10.0.0.1:6379#VN0
                long positionOnRing = hash(virtualNodeName);
                hashRing.put(positionOnRing, nodeName); // 环上钉的是分身位置，但记录的是真身
            }
        }

        /** 物理节点下环：把它所有的分身从环上拔掉。 */
        void removeNode(String nodeName) {
            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualNodeName = nodeName + "#VN" + i;
                hashRing.remove(hash(virtualNodeName));
            }
        }

        /**
         * 给一个 key 找归宿：
         *   第 1 步：算出 key 在环上的位置
         *   第 2 步：顺时针找第一个 >= 该位置的节点（tailMap 就是"从这个位置往后的所有节点"）
         *   第 3 步：如果后面没有节点了（走到钟表盘 12 点之后），绕回环的开头取第一个
         */
        String route(String key) {
            if (hashRing.isEmpty()) {
                throw new IllegalStateException("环上还没有任何节点");
            }
            long keyPosition = hash(key);
            SortedMap<Long, String> clockwisePart = hashRing.tailMap(keyPosition);
            if (clockwisePart.isEmpty()) {
                // 绕回环的开头——这就是"环形"两个字的体现
                return hashRing.firstEntry().getValue();
            }
            return clockwisePart.get(clockwisePart.firstKey());
        }

        /**
         * 哈希函数：用 MD5 取前 4 个字节拼成一个数（0 ~ 2^32-1）。
         * 为什么不用 String.hashCode()？因为它分布不均匀，节点会在环上"扎堆"。
         * 工业界的 Ketama 算法也是用 MD5，这里跟真实实现对齐。
         */
        static long hash(String text) {
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                byte[] digest = md5.digest(text.getBytes(StandardCharsets.UTF_8));
                // 取前 4 个字节，拼成一个 0 ~ 2^32-1 的无符号数
                long result = 0;
                for (int i = 0; i < 4; i++) {
                    result = (result << 8) | (digest[i] & 0xFF);
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException("MD5 初始化失败", e);
            }
        }
    }

    // 实验用的 key 数量：模拟 1 万个缓存数据
    static final int KEY_COUNT = 10_000;

    public static void main(String[] args) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            keys.add("user:session:" + i); // 模拟 1 万个缓存 key
        }

        experimentOne_NormalHash(keys);
        experimentTwo_ConsistentHash(keys);
        experimentThree_VirtualNodes(keys);

        System.out.println();
        System.out.println("全部实验完成：一致性 Hash 扩容时错位少、加虚拟节点后分布均匀，与原题结论一致。");
    }

    /** 实验一：普通 Hash 取模，3 台扩到 4 台，看多少 key 错位。 */
    static void experimentOne_NormalHash(List<String> keys) {
        System.out.println("========== 实验一：普通 Hash 取模（hash % N）扩容 ==========");

        int movedCount = 0;
        for (String key : keys) {
            long keyHash = ConsistentHashRouter.hash(key);
            int nodeBefore = (int) (keyHash % 3); // 扩容前：3 台机器
            int nodeAfter = (int) (keyHash % 4);  // 扩容后：4 台机器
            if (nodeBefore != nodeAfter) {
                movedCount++; // 归属变了 = 老位置找不到数据 = 缓存未命中
            }
        }

        double movedPercent = 100.0 * movedCount / keys.size();
        System.out.printf("3 台扩到 4 台后，%d 个 key 中有 %d 个错位，占 %.1f%%%n",
                keys.size(), movedCount, movedPercent);
        System.out.println("结论：绝大多数缓存瞬间失效，请求全部砸向数据库（缓存被击穿）。");
        System.out.println();
    }

    /** 实验二：一致性 Hash，同样 3 台扩到 4 台，看错位比例。 */
    static void experimentTwo_ConsistentHash(List<String> keys) {
        System.out.println("========== 实验二：一致性 Hash 扩容 ==========");

        // 扩容前：3 台机器上环（每台 150 个虚拟节点）
        ConsistentHashRouter routerBefore = new ConsistentHashRouter(150);
        routerBefore.addNode("10.0.0.1:6379");
        routerBefore.addNode("10.0.0.2:6379");
        routerBefore.addNode("10.0.0.3:6379");

        // 记下每个 key 扩容前的归属
        Map<String, String> nodeBeforeMap = new HashMap<>();
        for (String key : keys) {
            nodeBeforeMap.put(key, routerBefore.route(key));
        }

        // 扩容：加入第 4 台机器
        routerBefore.addNode("10.0.0.4:6379");

        // 对比扩容前后的归属
        int movedCount = 0;
        int movedToNewNode = 0;
        for (String key : keys) {
            String nodeAfter = routerBefore.route(key);
            if (!nodeAfter.equals(nodeBeforeMap.get(key))) {
                movedCount++;
                if (nodeAfter.equals("10.0.0.4:6379")) {
                    movedToNewNode++;
                }
            }
        }

        double movedPercent = 100.0 * movedCount / keys.size();
        System.out.printf("3 台扩到 4 台后，%d 个 key 中只有 %d 个挪了窝，占 %.1f%%%n",
                keys.size(), movedCount, movedPercent);
        System.out.printf("其中 %d 个是被新节点接管的（理论上挪窝的应该全是给新节点的）%n", movedToNewNode);
        System.out.println("结论：只有约 1/4 的数据需要迁移，其余原地不动，数据库压力平稳。");
        System.out.println();
    }

    /** 实验三：虚拟节点少 vs 多，看 3 台机器分到的 key 是否均匀。 */
    static void experimentThree_VirtualNodes(List<String> keys) {
        System.out.println("========== 实验三：虚拟节点治\"数据倾斜\" ==========");

        System.out.println("--- 每台只有 1 个虚拟节点（相当于没用虚拟节点）---");
        printDistribution(buildThreeNodeRouter(1), keys);

        System.out.println("--- 每台 150 个虚拟节点 ---");
        printDistribution(buildThreeNodeRouter(150), keys);

        System.out.println("结论：分身多了之后，三台机器分到的数据接近各占 1/3，不再贫富悬殊。");
    }

    /** 搭一个 3 台机器的路由器，虚拟节点数量由参数决定。 */
    static ConsistentHashRouter buildThreeNodeRouter(int virtualNodeCount) {
        ConsistentHashRouter router = new ConsistentHashRouter(virtualNodeCount);
        router.addNode("10.0.0.1:6379");
        router.addNode("10.0.0.2:6379");
        router.addNode("10.0.0.3:6379");
        return router;
    }

    /** 统计并打印每台机器分到了多少 key。 */
    static void printDistribution(ConsistentHashRouter router, List<String> keys) {
        Map<String, Integer> counter = new TreeMap<>(); // TreeMap 让输出按节点名排序，好看
        for (String key : keys) {
            counter.merge(router.route(key), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            double percent = 100.0 * entry.getValue() / keys.size();
            System.out.printf("  %s 分到 %d 个 key（%.1f%%）%n", entry.getKey(), entry.getValue(), percent);
        }
    }
}
