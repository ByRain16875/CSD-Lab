# 验证记录（Main -> Gateway 改造）

## 1. 验证目标

验证从 `agent.gw.Main` 迁移到 `agent.gw.Gateway` 后的以下特性：
1. 正常流程（START -> TOKEN* -> DONE）的正确性保持不变
2. 背压闭环（`writable=false` 入队、恢复可写后冲刷）是否生效
3. 入口过载保护（`MAX_INFLIGHT`、工作队列上限）是否生效
4. 会话生命周期管理（单次回收、并发安全）是否正确
5. 可观测性指标是否覆盖改造后的关键路径

## 2. 测试环境

- **硬件环境**：Intel Core i5-8250U, 8GB RAM
- **软件环境**：Java 17, Maven 3.8.6, Netty 4.1.96.Final
- **网络环境**：本地局域网，延迟<1ms
- **服务实现**：`src/main/java/agent/gw/Gateway.java`

## 3. 测试方案

### 3.1 正常客户端测试
- **测试工具**：`NormalClient.java`
- **测试步骤**：
  1. 启动 `Gateway`
  2. 运行正常客户端，发送 START 请求
  3. 记录客户端与服务器日志
  4. 验证 TOKEN 序列与 DONE 终态

### 3.2 慢客户端/背压测试
- **测试工具**：`SlowClient.java`
- **测试步骤**：
  1. 启动 `Gateway`
  2. 运行慢客户端（增加消费延迟）
  3. 观察 `writable`、`qlen`、`event=error code=OVERLOADED reason=queue_limit`
  4. 验证恢复可写后 `flushQueued` 行为

### 3.3 负载测试
- **测试工具**：`LoadTestClient.java`
- **测试步骤**：
  1. 启动 `Gateway`
  2. 运行负载客户端（并发 + 多请求）
  3. 记录 `server_new.log` 与 `load_new.log`
  4. 分析完成率、延迟、吞吐、连接峰值

### 3.4 入口保护与并发安全测试
- **测试工具**：`LoadTestClient.java` + 定向构造请求
- **测试步骤**：
  1. 提升并发直到接近 `MAX_INFLIGHT` 或工作队列饱和
  2. 验证入口是否返回 `OVERLOADED`（`gateway_busy` / `worker_queue_full`）
  3. 验证重复 `sessionId` 返回 `INVALID_REQUEST`（`duplicate_session_id`）
  4. 验证会话结束后 `inflight` 不出现异常回收

## 4. 性能指标

### 4.1 主指标
- **请求完成率**：成功完成 START -> TOKEN* -> DONE 的请求比例
- **平均响应时间**：从发送 START 到首个终态（DONE 或 ERROR）的平均时间
- **错误率**：发生 ERROR 的请求比例
- **队列使用率**：`qlen / queueLimit` 的使用水平

### 4.2 辅指标
- **p95延迟**：95% 请求的终态延迟
- **活跃线程数**：`worker` 去重计数
- **消息吞吐量**：单位时间 token 处理量
- **连接数**：并发连接峰值
- **过载拒绝次数**：`OVERLOADED` 事件次数（按 reason 分类）

## 5. 测试结果

> 说明：以下“已测”数据来自当前仓库日志与既有记录（`logs/server_new.log`、`logs/load_new.log`、`VERIFICATION_RECORD.md`、`EVIDENCE_CHAIN_2.md`）；“待复测”为本轮改造验证建议补测项。

### 5.1 正常客户端测试结果（已测）

| 指标 | 值 | 说明 |
|------|-----|------|
| 请求完成率 | 100.00% | DONE请求数/总请求数 (1/1) |
| 平均响应时间 | 643.0ms | START到首个终态平均时长 |
| 错误率 | 0.00% | ERROR请求数/总请求数 (0/1) |
| 队列使用率 | 0.00% | 平均 qlen/32，最大=0.00% |
| p95延迟 | 643.0ms | 95分位终态延迟 |
| 活跃线程数 | 1 (观测) | route 日志中 worker 去重 |
| 消息吞吐量 | 10.89 msg/s | token总数/日志时间窗口 |
| 连接数 | 峰值约 1 | route(+1)/终态(-1) 估算 |

### 5.2 慢客户端/背压测试结果（部分已测）

| 指标 | 值 | 说明 |
|------|-----|------|
| 背压入队行为 | 已观测 | 证据链中存在 `writable=false` 且 `qlen` 递增样例 |
| 队列溢出保护 | 已观测 | 存在 `event=error code=OVERLOADED reason=queue_limit` 样例 |
| 完成率 | 待复测 | 需基于 SlowClient 最新版本重跑并统计 |
| 平均响应时间 | 待复测 | 需输出 START->ERROR/DONE 的真实分布 |
| 错误率 | 待复测 | 建议统计 OVERLOADED 占比 |
| 队列使用率 | 待复测 | 建议按 qlen/32 计算平均与峰值 |

### 5.3 负载测试结果（已测）

| 指标 | 值 | 说明 |
|------|-----|------|
| 请求完成率 | 100.00% | DONE请求数/总请求数 (120/120) |
| 平均响应时间 | 10.07s | START到首个终态平均时长 |
| 错误率 | 0.00% | ERROR请求数/总请求数 (0/120) |
| 队列使用率 | 0.00% | 平均 qlen/32，最大=0.00% |
| p95延迟 | 18.74s | 95分位终态延迟 |
| 活跃线程数 | 4 (观测) | route 日志中 worker 去重 |
| 消息吞吐量 | 43.25 msg/s | token总数/日志时间窗口(19.42s) |
| 连接数 | 峰值约 120 | route(+1)/终态(-1) 估算 |

### 5.4 入口保护与并发安全结果（部分已测）

| 检查项 | 结果 | 说明 |
|------|-----|------|
| 入口过载拒绝 (`gateway_busy`) | 已观测样例 | 见证据链中的入口拒绝日志 |
| 工作队列拒绝 (`worker_queue_full`) | 待复测 | 需构造更高突发并记录 error reason |
| 重复会话保护 (`duplicate_session_id`) | 待复测 | 需构造重复 sessionId 请求 |
| inflight 单次回收 | 代码已实现，待压测确认 | `completeSession` + `released` 防重复回收 |

## 6. 分析与结论

### 6.1 正常流程保持性
- **结果**：Main 迁移到 Gateway 后，正常 START -> TOKEN* -> DONE 行为保持稳定。
- **分析**：流式发送从阻塞式 sleep 转为 EventLoop 定时调度，语义保持一致。
- **结论**：功能兼容性验证通过。

### 6.2 背压闭环能力
- **结果**：已出现不可写入队与 `queue_limit` 过载错误样例。
- **分析**：`channelWritabilityChanged` + `flushQueued` 形成“不可写入队、可写冲刷”闭环。
- **结论**：背压机制较 Main 版本更完整，但需补齐慢客户端端到端量化结果。

### 6.3 高负载能力
- **结果**：当前 120 请求样本下完成率 100%，线程利用达到 4 worker。
- **分析**：有界线程池与入口保护降低了积压失控风险。
- **结论**：在当前压测强度下表现稳定，仍需更高压场景验证 `worker_queue_full`。

### 6.4 并发安全与可观测性
- **结果**：会话管理改为并发容器，回收路径统一；日志覆盖 route/token/error 核心字段。
- **分析**：相比 Main 版本，降低了并发下状态不一致的风险，定位问题更直接。
- **结论**：并发正确性与可观测性均有提升。

## 7. 策略改善与代价

### 7.1 改善点
- **入口治理**：`MAX_INFLIGHT` + 工作队列上限，提前拒绝过载请求。
- **背压治理**：基于 channel 可写性驱动会话队列与冲刷。
- **执行模型**：EventLoop 定时流式发送，降低阻塞与线程竞争。
- **生命周期治理**：`released` + `completeSession` 防止重复回收。

### 7.2 代价与风险
- **请求拒绝增加**：高峰时可能更早返回 `OVERLOADED`，需客户端重试策略配合。
- **参数调优成本**：`MAX_INFLIGHT`、`QUEUE_LIMIT`、水位线需结合业务流量调优。
- **观测门槛提升**：需结合多维日志（route/token/error）做综合分析。

## 8. 总结

本次 `Main` 到 `Gateway` 的改造验证表明：

1. **协议正确性保持**：正常流式语义未回退。
2. **抗压能力增强**：入口限流 + 有界线程池 + 会话队列形成多层保护。
3. **背压机制完整**：可写性变化触发入队与冲刷，避免无限堆积。
4. **并发一致性提升**：会话回收路径统一，减少 inflight 统计失真风险。
5. **后续工作明确**：补齐 `worker_queue_full`、`duplicate_session_id`、SlowClient 量化复测后，可形成最终验收版记录。

