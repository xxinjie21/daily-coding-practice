import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最简负载均衡器 Demo（严格按原题给出的方法实现）
 *
 * 原题方法：
 *  1) 轮询：维护一个自增计数器，每次请求取 (i++) % servers.size() 打到对应机器
 *  2) 加权轮询：按权重把节点展开成列表，例如 A:3 B:1 -> [A,A,A,B]，再轮询
 *
 * 这里在最小化代码的前提下补全为可独立编译运行的完整示例。
 */
public class Demo {

    /** 轮询负载均衡器：用 AtomicInteger 保证并发安全 */
    static class RoundRobin {
        // 原题示例：List<String> servers = Arrays.asList("192.168.0.1", "192.168.0.2", "192.168.0.3");
        private final List<String> servers;
        private final AtomicInteger idx = new AtomicInteger(0);

        RoundRobin(List<String> servers) {
            this.servers = servers;
        }

        // 原题方法：public String getNextServer() { int i = idx.getAndIncrement(); return servers.get(i % servers.size()); }
        public String getNextServer() {
            int i = idx.getAndIncrement();          // 原子自增，多线程安全
            return servers.get(i % servers.size()); // 对节点数取模，实现轮询
        }
    }

    /** 加权轮询负载均衡器：按权重展开节点列表后再轮询（原题方法） */
    static class WeightedRoundRobin {
        private final List<String> expanded = new ArrayList<>();
        private final AtomicInteger idx = new AtomicInteger(0);

        /**
         * @param weightedServers 形如 {"192.168.0.1":3, "192.168.0.2":1}
         */
        WeightedRoundRobin(java.util.Map<String, Integer> weightedServers) {
            // 按权重展开：A:3, B:1 -> [A, A, A, B]
            for (java.util.Map.Entry<String, Integer> entry : weightedServers.entrySet()) {
                for (int k = 0; k < entry.getValue(); k++) {
                    expanded.add(entry.getKey());
                }
            }
        }

        public String getNextServer() {
            int i = idx.getAndIncrement();
            return expanded.get(i % expanded.size());
        }
    }

    public static void main(String[] args) {
        // === 1. 轮询演示 ===
        RoundRobin rr = new RoundRobin(
                Arrays.asList("192.168.0.1", "192.168.0.2", "192.168.0.3"));
        System.out.println("=== 轮询（Round-Robin）===");
        for (int i = 0; i < 6; i++) {
            System.out.println("请求 " + (i + 1) + " -> " + rr.getNextServer());
        }

        // === 2. 加权轮询演示（A 权重 3，B 权重 1）===
        java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        weights.put("192.168.0.1", 3);
        weights.put("192.168.0.2", 1);
        WeightedRoundRobin wrr = new WeightedRoundRobin(weights);
        System.out.println("\n=== 加权轮询（A:3, B:1）===");
        for (int i = 0; i < 8; i++) {
            System.out.println("请求 " + (i + 1) + " -> " + wrr.getNextServer());
        }
    }
}
