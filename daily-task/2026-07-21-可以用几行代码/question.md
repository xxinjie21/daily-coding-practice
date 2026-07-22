## 1. 可以用几行代码实现一个负载均衡器吗？

实现一个最简负载均衡器，核心是选节点的策略。我们用 Java 写个轮询的例子，10 行代码左右就能跑起来。

维护一个计数器，每次有请求就取 (i++) % servers.size() ，把流量打到对应机器上。这就是轮询，最简单的 负载策略。

List<String> servers = Arrays.asList("192.168.0.1", "192.168.0.2", "192.168.0.3"); AtomicInteger idx = new AtomicInteger(0);

public String getNextServer() { int i = idx.getAndIncrement(); return servers.get(i % servers.size()); }

如果要加权重，可以按权重展开成列表，比如 A:3, B:1 就生成 [A,A,A,B]，再轮询。这种叫加权轮询。

实际生产不会这么简单。Nginx 用的是平滑加权轮询，Spring Cloud LoadBalancer 提供了响应时间权重、随机、最少 活跃等策略。Zookeeper 或 Nacos 下发节点列表，配合健康检查自动剔除故障实例。

这类组件的关键是无状态，能独立部署。像 Ribbon 这种客户端负载均衡，直接在 JVM 里完成选择，省去一次网络跳 转。
