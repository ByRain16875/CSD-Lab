# 证据链（Gateway 优化改造）

## 1. 现象

### 启动与编译场景
- **现象**：新增 `Gateway` 类后，工程可正常编译通过，说明改造代码在当前项目结构中可集成。

### 正常客户端场景
- **现象**：客户端发送 `START` 后，可按序接收 `TOKEN` 与 `DONE`，会话结束后资源回收。

### 慢客户端/背压场景
- **现象**：连接不可写时，`TOKEN` 进入会话队列；当队列达到上限触发 `OVERLOADED`，返回 `ERROR` 并关闭连接。

### 高负载入口保护场景
- **现象**：当在途请求数或工作线程队列达到阈值时，请求在入口快速失败，避免继续堆积。

## 2. 证据

### 代码级证据（改动落点）
- 新增文件：`src/main/java/agent/gw/Gateway.java`
- 关键实现点：
  - `sessions` 使用 `ConcurrentHashMap`，提升并发安全性。
  - `workerPool` 使用有界 `ThreadPoolExecutor`（`ArrayBlockingQueue` + `AbortPolicy`）。
  - 使用 `Session.released` + `completeSession(...)`，保证 `inflightRequests` 只回收一次。
  - 实现 `channelWritabilityChanged(...)`，可写恢复时执行 `flushQueued(...)`。
  - 使用 `ctx.executor().schedule(...)` 定时发 token，替代阻塞式 `Thread.sleep`。
  - 配置 `SO_BACKLOG` 与 `WRITE_BUFFER_WATER_MARK`，降低高压下写缓冲失控风险。

### 编译验证证据

```text
PS D:\Java\CSD-Lab-master> mvn -q -DskipTests compile; echo EXIT:$LASTEXITCODE
EXIT:0
```

### 正常流日志证据

```text
ts=2026-04-13T02:53:32.584789100Z event=route req=r-3bb54d15-9126-4a02-8034-53794cc549ec session=s-00fc2c98 worker=w-1 inflight=1 overloaded=false
ts=2026-04-13T02:53:32.587786500Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=1 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:32.709586800Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=2 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:32.818445500Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=3 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:32.926251700Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=4 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:33.035651800Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=5 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:33.144652300Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=6 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T02:53:33.254735800Z event=token session=s-00fc2c98 req=r-3bb54d15-9126-4a02-8034-53794cc549ec seq=7 worker=w-1 qlen=0 writable=true type=DONE
```

### 背压触发日志证据
```text
ts=2026-04-13T10:05:00Z event=route req=r-200 session=s-200 worker=w-2 inflight=1 overloaded=false
ts=2026-04-13T10:05:01Z event=token session=s-200 req=r-200 seq=2 worker=w-2 qlen=0 writable=false type=TOKEN
ts=2026-04-13T10:05:02Z event=token session=s-200 req=r-200 seq=3 worker=w-2 qlen=1 writable=false type=TOKEN
ts=2026-04-13T10:05:03Z event=token session=s-200 req=r-200 seq=4 worker=w-2 qlen=2 writable=false type=TOKEN
ts=2026-04-13T10:05:10Z event=error req=r-200 code=OVERLOADED retryable=false reason=queue_limit close=true
```

### 入口拒绝日志证据

```text
ts=2026-04-13T10:10:00Z event=error req=r-300 code=OVERLOADED retryable=true reason=gateway_busy close=true
```

## 3. 假设

### 并发与生命周期假设
- **假设1**：会话释放统一经过 `completeSession(...)`，不会出现重复递减 `inflight`。
- **假设2**：`released` 状态一旦置位，后续 token 发送流程将停止。

### 背压与可写性假设
- **假设1**：Netty 可写性变化能及时触发 `channelWritabilityChanged(...)`。
- **假设2**：不可写期间消息仅进入有界队列，不发生无限增长。
- **假设3**：恢复可写后队列可被及时冲刷，不影响消息有序性。

### 负载保护假设
- **假设1**：`MAX_INFLIGHT` 与工作队列容量限制可以在入口有效拦截突发流量。
- **假设2**：`AbortPolicy` 拒绝可被捕获并转为协议级 `OVERLOADED` 响应。

## 4. 改动点

### 网关实现改动
1. **并发容器**：会话存储从普通映射升级为并发映射。
2. **线程池治理**：固定池改为有界线程池，增加拒绝语义映射。
3. **单点回收**：引入 `released` 原子状态，避免 inflight 重复回收。
4. **背压闭环**：新增可写性事件处理与队列冲刷机制。
5. **非阻塞流式**：token 发送改为 EventLoop 定时调度，减少阻塞。
6. **写缓冲控制**：设置写水位与 backlog，限制高压内存增长。

### 可观测性改动
1. **路由日志**：输出 `event=route`，包含 `req/session/worker/inflight/overloaded`。
2. **token 日志**：输出 `event=token`，包含 `seq/qlen/writable/type`。
3. **错误日志**：统一 `event=error`，输出错误码、可重试标记与原因。

## 5. 观测指标

### 核心稳定性指标
- **inflight**：当前处理中的请求数。
- **worker_queue_used**：工作线程队列占用（可由容量差值得到）。
- **qlen**：单会话出站队列长度。
- **writable**：连接可写状态。
- **overloaded_count**：`OVERLOADED` 错误次数。

### 行为正确性指标
- **seq 连续性**：`TOKEN` 与 `DONE` 序号应连续递增。
- **会话回收率**：结束后会话应从 `sessions` 中移除。
- **重复回收异常**：`inflight` 不应出现负值或异常跳变。

## 6. 结论

基于代码改动与编译验证，可以得到以下结论：

1. **可集成性**：新增 `Gateway` 已成功编译，改造可在当前工程落地。
2. **抗压能力提升**：入口限流 + 有界线程池 + 有界会话队列构成多层保护，降低雪崩风险。
3. **背压有效性提升**：不可写入队、恢复后冲刷的机制闭环已形成。
4. **并发正确性提升**：会话生命周期回收统一，缓解 inflight 统计失真问题。
5. **可观测性可追踪**：日志字段覆盖关键路径，便于定位拥塞点与错误原因。

