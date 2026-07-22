# 总结：负载均衡器（轮询 / 加权轮询）

## 线上踩坑点
1. **计数器非原子导致脏读**：若用 `int i++` 而非 `AtomicInteger`，并发下多个线程会拿到相同下标，把请求打到同一台机器甚至越界。必须用原子类或在同步块内取模。
2. **取模溢出**：`idx` 一直自增，长时间运行后 `Integer.MAX_VALUE` 回绕到负数，`i % size` 仍正确（Java 取模对负数也返回非负结果当除数为正），但为保险可对 `idx` 做 `getAndIncrement() & Integer.MAX_VALUE` 或取模后再自增。
3. **加权轮询"连发"问题**：本 demo 用"按权重展开列表"实现加权轮询，权重高的节点会**连续**被打满（A,A,A,B）。生产环境更常用 **Nginx 平滑加权轮询**，分散请求、避免瞬时压垮强节点。
4. **节点静态写死**：`Arrays.asList(...)` 无法感知上下线。真实系统需注册中心（Nacos/Zookeeper）下发列表 + 定时健康检查自动摘除故障节点，否则会把流量转发到已宕机的机器。
5. **无优雅关闭**：节点下线瞬间仍有在途请求，需要熔断/重试兜底。

## 同类场景拓展
- **随机策略**：`servers.get(ThreadLocalRandom.current().nextInt(size))`，实现简单、分散性好，但无法保证均匀。
- **最少活跃请求数（Least Active）**：记录每台节点正在处理的请求数，挑最闲的，更贴近真实负载（Dubbo/LB 常用）。
- **一致性哈希**：对 `requestKey.hashCode() % servers.size()` 取模，相同 key 始终落到同一节点，适合有状态/缓存命中场景；节点扩缩容时仅影响少量 key。
- **响应时间权重**：按节点历史 RT 动态调权，RT 越低权重越高（Spring Cloud LoadBalancer 支持）。
- **客户端 vs 服务端负载均衡**：Ribbon 这类在调用方 JVM 内选择节点（省一次网络跳转）；Nginx/LVS 在服务端前置分发。两者常组合使用（入口 LVS → 网关 Nginx → 服务内 Ribbon）。
- **进阶：服务网格**：Istio/Envoy 把负载均衡下沉到 Sidecar，业务代码零感知。
