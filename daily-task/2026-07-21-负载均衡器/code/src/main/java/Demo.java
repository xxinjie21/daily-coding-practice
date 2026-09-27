// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   题目是「怎么用几行代码，让一堆请求轮流分给多台服务器，别让一台累死、其他闲着」。
//   演示两种最常用的负载均衡算法：
//     ① 轮询（Round-Robin）—— 3 台服务器轮流接客：1、2、3、1、2、3……
//     ② 加权轮询（Weighted Round-Robin）—— 机器有强有弱，强的多分几单
//
// 前置条件：纯算法，不连任何中间件。
//
// 生活比喻：餐厅里 3 个服务员（= 3 台服务器），客人（= 请求）不断进来，
//          领班定个规矩「下一个客人给谁接」—— 轮流来就是轮询。

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    // 轮询：给每个请求发一个号，用「号 % 机器数」决定分给哪台。
    // 3 台机器时号码走 0,1,2,3,4,5 → 余数 0,1,2,0,1,2 → 正好轮着来。
    static class RoundRobin {
        private final List<String> servers;

        // 取号机必须用 AtomicInteger：多线程同时来也不会发重号、跳号，
        // 换成 int i++ 就会出现两个人拿到同一个号。
        private final AtomicInteger counter = new AtomicInteger();

        RoundRobin(List<String> servers) {
            this.servers = servers;
        }

        String getNextServer() {
            int index = counter.getAndIncrement() % servers.size();
            return servers.get(index);
        }
    }

    // 加权轮询：把「服务器 → 权重」展开成 [A, A, A, B]，再像轮询一样从头走到尾，
    // A 自然比 B 多分到 3 倍流量。
    static class WeightedRoundRobin {
        private final List<String> expandedServers = new ArrayList<>();
        private final AtomicInteger counter = new AtomicInteger();

        WeightedRoundRobin(Map<String, Integer> weights) {
            weights.forEach((server, weight) -> {
                for (int i = 0; i < weight; i++) {
                    expandedServers.add(server);
                }
            });
        }

        String getNextServer() {
            int index = counter.getAndIncrement() % expandedServers.size();
            return expandedServers.get(index);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 普通轮询（3 台服务器轮流接客）===");
        RoundRobin roundRobin = new RoundRobin(
                List.of("192.168.0.1", "192.168.0.2", "192.168.0.3"));
        for (int i = 1; i <= 6; i++) {
            System.out.println("第 " + i + " 个请求 -> " + roundRobin.getNextServer());
        }

        System.out.println();
        System.out.println("=== 加权轮询（A:3 份, B:1 份）===");
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("192.168.0.1", 3);   // A 机器强，分 3 份
        weights.put("192.168.0.2", 1);   // B 机器弱，分 1 份
        WeightedRoundRobin weighted = new WeightedRoundRobin(weights);
        for (int i = 1; i <= 8; i++) {
            System.out.println("第 " + i + " 个请求 -> " + weighted.getNextServer());
        }
    }
}
