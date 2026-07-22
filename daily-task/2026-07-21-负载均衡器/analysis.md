# 分析：几行代码实现一个负载均衡器

## 一、考点拆解
1. **负载均衡本质**：在多个服务节点间按策略分发请求，追求高可用与横向扩展。
2. **轮询（Round-Robin）**：最朴素策略，核心 = 自增计数器对节点数取模。
3. **并发安全**：计数器在多线程下必须原子自增，否则重复选取或越界。
4. **加权轮询（Weighted RR）**：节点性能不均时按权重分配流量，避免弱机被打垮。
5. **工程化扩展**：节点发现（注册中心）、健康检查、故障剔除、重试。

## 二、业务痛点
- 单节点容量有限，流量集中会拖垮服务，需要把请求摊开到集群。
- 朴素轮询"一视同仁"，但机器配置不同，需要权重区分。
- 节点会上下线，硬编码 `Arrays.asList(...)` 无法动态感知，需注册中心下发 + 健康探测。
- 无健康检查会把请求转发到已宕机节点，出现大量失败。

## 三、具体实现方法（按原题思路落地，可直接照做）

> 原题给出的最小实现 = **轮询 + 原子计数器**，并扩展到 **加权轮询（按权重展开）**。下面是可照做的步骤与要点。

**Step 1 · 数据结构**
```java
List<Server> servers = Arrays.asList(s1, s2, s3); // 节点列表
AtomicInteger idx = new AtomicInteger(0);          // 原子计数器，保证并发安全
```

**Step 2 · 轮询核心（O(1)）**
```java
int i = idx.getAndIncrement();          // 原子自增，多线程安全
Server s = servers.get(i % servers.size()); // 对节点数取模，循环分发
```
- 必须用 `AtomicInteger`，**不能用 `int i++`**（并发下多个线程会拿到相同下标）。
- `i` 一直自增，长时间后 `Integer.MAX_VALUE` 回绕到负数，但 Java 取模对正数除数仍返回非负结果（`i % size` 正确），稳妥起见可对 `idx` 做 `getAndIncrement() & Integer.MAX_VALUE`。

**Step 3 · 加权轮询（展开法，原题方法）**
- 按权重把节点复制进列表：`A:3, B:1 -> [A, A, A, B]`，再走轮询。
- 缺点：权重高的节点会**连续**被打满（A,A,A,B）。

**Step 4 · 平滑加权轮询（生产改进，Nginx 式）**
为解决"连发"，生产常用**平滑加权轮询**，核心是每个节点维护 `currentWeight`，每轮选当前权重最大者，选中后 `currentWeight -= 总权重`：
```
初始化：each.currentWeight = 0; total = Σweight
每轮：
  for n in nodes: n.currentWeight += n.effectiveWeight
  best = currentWeight 最大的节点
  best.currentWeight -= total
  返回 best
```
这样权重 5:3:2 的节点序列会是 `a,b,a,c,a,b,a,...`，请求被均匀打散，不会连续压满强节点。

**Step 5 · 工程补齐（在最小实现之上）**
1. **节点发现**：用 Nacos / Zookeeper / Eureka 下发可用节点列表，替代硬编码 `Arrays.asList`。
2. **健康检查**：定时探活（TCP/HTTP ping），自动把故障节点从列表摘除。
3. **策略升级**：Spring Cloud LoadBalancer 提供响应时间权重、最少活跃请求数；客户端 LB（Ribbon）在 JVM 内选节点，省一次网络跳转。
4. **失败兜底**：重试 + 熔断（如 Resilience4j），节点下线瞬间保护在途请求。

## 四、本仓库 Demo 对应说明
`code/Demo.java` 严格按原题方法实现：**轮询（原子计数器取模）+ 加权轮询（按权重展开）**，构成可独立运行的最小 demo。生产级的平滑加权、注册中心、健康检查属于 Step 4/5 的工程扩展，不在最小 demo 范围内，但思路完全一致。
