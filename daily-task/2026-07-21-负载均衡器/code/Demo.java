// ============================================================
// 负载均衡器 Demo（Java 17）
// 编译运行：javac --release 17 Demo.java && java Demo
//
// 【这个程序在干嘛？】
// 想象银行有 3 个窗口（服务器），客户（请求）来了要分到某个窗口。
// 怎么分才公平？这就是"负载均衡"。
// 我们演示两种最常用的方法（严格按原题给的方法实现）：
//   方法 1：轮询（Round-Robin）—— 窗口轮流叫号：1、2、3、1、2、3……
//   方法 2：加权轮询（Weighted Round-Robin）—— 大窗口能力强，多分它几单
// ============================================================

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    // ----------------------------------------------------------
    // 方法 1：轮询负载均衡器
    // ----------------------------------------------------------
    // 思路：
    //   准备一个"服务器清单" servers，再准备一个永远 +1 的计数器 counter。
    //   每来一个请求，取 counter 当前值对服务器数量取余数，余数就是该打到的下标。
    //   然后把 counter +1，下次请求就打到下一个。
    //   例：3 台机器时 counter 走 0,1,2,3,4,5 → 余数 0,1,2,0,1,2 → 正好轮着来。
    static class RoundRobin {

        // 服务器清单，例如 ["192.168.0.1", "192.168.0.2", "192.168.0.3"]
        private final List<String> servers;

        // 计数器：每来一个请求就 +1。
        // 用 AtomicInteger 是为了"原子自增"，多线程同时来也不会数错。
        private final AtomicInteger counter = new AtomicInteger(0);

        // 构造方法：把服务器清单存进来
        RoundRobin(List<String> servers) {
            this.servers = servers;
        }

        // 给下一个请求分配一台服务器
        public String getNextServer() {
            int current = counter.getAndIncrement();   // 先拿到当前值，再 +1
            int index = current % servers.size();       // 对服务器数量取余，得到下标
            return servers.get(index);                  // 返回这台服务器
        }
    }

    // ----------------------------------------------------------
    // 方法 2：加权轮询负载均衡器
    // ----------------------------------------------------------
    // 思路：
    //   机器有强有弱，按"权重"把清单"展开"。
    //   比如 A 权重 3、B 权重 1，就展开成 [A, A, A, B]。
    //   然后像轮询一样从头打到尾，A 自然比 B 多分到 3 倍流量。
    static class WeightedRoundRobin {

        // 展开后的清单，例如 [A, A, A, B]
        private final List<String> expandedServers = new ArrayList<>();

        // 同样用计数器做轮询
        private final AtomicInteger counter = new AtomicInteger(0);

        // 构造方法：传入"服务器 -> 权重"的映射
        WeightedRoundRobin(java.util.Map<String, Integer> weightedServers) {
            // 遍历每个服务器，按权重往清单里塞对应数量的名字
            for (java.util.Map.Entry<String, Integer> entry : weightedServers.entrySet()) {
                String server = entry.getKey();   // 服务器名，如 "192.168.0.1"
                int weight = entry.getValue();     // 它的权重，如 3
                for (int i = 0; i < weight; i++) {
                    expandedServers.add(server);   // 权重是几就加几次
                }
            }
        }

        public String getNextServer() {
            int current = counter.getAndIncrement();
            int index = current % expandedServers.size();
            return expandedServers.get(index);
        }
    }

    public static void main(String[] args) {
        // ===== 演示 1：普通轮询（3 台机器）=====
        RoundRobin rr = new RoundRobin(
                Arrays.asList("192.168.0.1", "192.168.0.2", "192.168.0.3"));
        System.out.println("=== 普通轮询（3 台服务器轮流接客）===");
        for (int i = 1; i <= 6; i++) {
            System.out.println("第 " + i + " 个请求 -> " + rr.getNextServer());
        }

        // ===== 演示 2：加权轮询（A 权重 3，B 权重 1）=====
        java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        weights.put("192.168.0.1", 3);  // A 机器强，分 3 份
        weights.put("192.168.0.2", 1);  // B 机器弱，分 1 份
        WeightedRoundRobin wrr = new WeightedRoundRobin(weights);
        System.out.println("\n=== 加权轮询（A:3 份, B:1 份）===");
        for (int i = 1; i <= 8; i++) {
            System.out.println("第 " + i + " 个请求 -> " + wrr.getNextServer());
        }
    }
}
