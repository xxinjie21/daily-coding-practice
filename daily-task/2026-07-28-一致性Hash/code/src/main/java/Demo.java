// 一致性 Hash Demo（纯算法，无需中间件）
//
// 演示"一致性 Hash 算法"，并和"普通 Hash 取模"做对比实验：
//   实验一：普通 Hash 取模，3 台扩 4 台，看多少数据错位（预期约 75%）
//   实验二：一致性 Hash，同样 3 台扩 4 台，看错位比例（预期约 25%，即新节点该接管的那部分）
//   实验三：虚拟节点少 vs 多，看数据在 3 台机器上分得均不均匀（治"数据倾斜"）
//
// 核心思路：
//   1) 把 0 ~ 2^32-1 想成一个首尾相接的"钟表盘"（Hash 环）
//   2) 服务器按 "IP:端口" 哈希后钉在环上；数据 key 哈希后也落在环上
//   3) 数据顺时针走，遇到的第一台服务器就是它的归宿
//   4) 每台物理机分身出多个"虚拟节点"撒在环上，让分布更均匀

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class Demo {

    static final int KEY_COUNT = 10_000; // 模拟 1 万个缓存 key

    public static void main(String[] args) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            keys.add("user:session:" + i);
        }

        experimentOne_NormalHash(keys);
        experimentTwo_ConsistentHash(keys);
        experimentThree_VirtualNodes(keys);
        System.out.println("\n全部实验完成：一致性 Hash 扩容时错位少、加虚拟节点后分布均匀，与原题结论一致。");
    }

    // ============================ 核心组件：一致性 Hash 路由器 ============================
    // 想象成"快递分拣台"：包裹(key)进来，它告诉你该送哪个网点(节点)。
    static class ConsistentHashRouter {
        // 环本体：TreeMap 自动按数字排序，能高效回答"大于等于 X 的第一个位置钉的是谁"，
        // 正好就是"顺时针找下一个节点"。key = 环上位置，value = 物理节点名。
        private final TreeMap<Long, String> hashRing = new TreeMap<>();
        private final int virtualNodeCount; // 每台物理机分身几个虚拟节点，越多越均匀（工业界常用 100~200）

        ConsistentHashRouter(int virtualNodeCount) {
            this.virtualNodeCount = virtualNodeCount;
        }

        // 上环：给物理节点生成 N 个分身，逐个哈希后钉到环上
        void addNode(String nodeName) {
            for (int i = 0; i < virtualNodeCount; i++) {
                hashRing.put(hash(nodeName + "#VN" + i), nodeName);
            }
        }

        // 给 key 找归宿：算出位置 -> 顺时针找第一个 >= 该位置的节点 -> 没有就绕回环首
        String route(String key) {
            if (hashRing.isEmpty()) {
                throw new IllegalStateException("环上还没有任何节点");
            }
            SortedMap<Long, String> clockwisePart = hashRing.tailMap(hash(key));
            // 走到环尾还没节点就绕回开头，这就是"环形"两个字的体现
            return clockwisePart.isEmpty()
                    ? hashRing.firstEntry().getValue()
                    : clockwisePart.get(clockwisePart.firstKey());
        }

        // 用 MD5 取前 4 字节拼成 0 ~ 2^32-1 的无符号数。
        // 不用 String.hashCode()：它分布不均，节点会在环上"扎堆"。工业界的 Ketama 也用 MD5。
        static long hash(String text) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(text.getBytes(StandardCharsets.UTF_8));
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

    // 实验一：普通 Hash 取模，3 台扩到 4 台，看多少 key 错位
    static void experimentOne_NormalHash(List<String> keys) {
        System.out.println("========== 实验一：普通 Hash 取模（hash % N）扩容 ==========");
        int movedCount = 0;
        for (String key : keys) {
            long keyHash = ConsistentHashRouter.hash(key);
            if ((int) (keyHash % 3) != (int) (keyHash % 4)) {
                movedCount++; // 归属变了 = 老位置找不到数据 = 缓存未命中
            }
        }
        System.out.printf("3 台扩到 4 台后，%d 个 key 中有 %d 个错位，占 %.1f%%%n",
                keys.size(), movedCount, 100.0 * movedCount / keys.size());
        System.out.println("结论：绝大多数缓存瞬间失效，请求全部砸向数据库（缓存被击穿）。\n");
    }

    // 实验二：一致性 Hash，同样 3 台扩到 4 台，看错位比例
    static void experimentTwo_ConsistentHash(List<String> keys) {
        System.out.println("========== 实验二：一致性 Hash 扩容 ==========");
        ConsistentHashRouter router = buildRouter(150, "10.0.0.1:6379", "10.0.0.2:6379", "10.0.0.3:6379");

        Map<String, String> nodeBeforeMap = new HashMap<>();
        for (String key : keys) {
            nodeBeforeMap.put(key, router.route(key));
        }

        router.addNode("10.0.0.4:6379"); // 扩容：加入第 4 台

        int movedCount = 0;
        int movedToNewNode = 0;
        for (String key : keys) {
            String nodeAfter = router.route(key);
            if (!nodeAfter.equals(nodeBeforeMap.get(key))) {
                movedCount++;
                if (nodeAfter.equals("10.0.0.4:6379")) {
                    movedToNewNode++;
                }
            }
        }
        System.out.printf("3 台扩到 4 台后，%d 个 key 中只有 %d 个挪了窝，占 %.1f%%%n",
                keys.size(), movedCount, 100.0 * movedCount / keys.size());
        System.out.printf("其中 %d 个是被新节点接管的（理论上挪窝的应该全是给新节点的）%n", movedToNewNode);
        System.out.println("结论：只有约 1/4 的数据需要迁移，其余原地不动，数据库压力平稳。\n");
    }

    // 实验三：虚拟节点少 vs 多，看 3 台机器分到的 key 是否均匀
    static void experimentThree_VirtualNodes(List<String> keys) {
        System.out.println("========== 实验三：虚拟节点治\"数据倾斜\" ==========");
        System.out.println("--- 每台只有 1 个虚拟节点（相当于没用虚拟节点）---");
        printDistribution(buildRouter(1, "10.0.0.1:6379", "10.0.0.2:6379", "10.0.0.3:6379"), keys);

        System.out.println("--- 每台 150 个虚拟节点 ---");
        printDistribution(buildRouter(150, "10.0.0.1:6379", "10.0.0.2:6379", "10.0.0.3:6379"), keys);
        System.out.println("结论：分身多了之后，三台机器分到的数据接近各占 1/3，不再贫富悬殊。");
    }

    static ConsistentHashRouter buildRouter(int virtualNodeCount, String... nodes) {
        ConsistentHashRouter router = new ConsistentHashRouter(virtualNodeCount);
        for (String node : nodes) {
            router.addNode(node);
        }
        return router;
    }

    // 统计并打印每台机器分到了多少 key（TreeMap 让输出按节点名排序，好看）
    static void printDistribution(ConsistentHashRouter router, List<String> keys) {
        Map<String, Integer> counter = new TreeMap<>();
        for (String key : keys) {
            counter.merge(router.route(key), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            System.out.printf("  %s 分到 %d 个 key（%.1f%%）%n",
                    entry.getKey(), entry.getValue(), 100.0 * entry.getValue() / keys.size());
        }
    }
}
