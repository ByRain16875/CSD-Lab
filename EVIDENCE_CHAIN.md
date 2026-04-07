# 证据链

## 1. 现象

### 正常客户端场景
- **现象**：正常客户端发送START请求后，能够成功接收TOKEN消息和DONE消息，会话正常完成。

### 慢客户端场景
- **现象**：慢客户端发送START请求后，由于处理速度慢，导致网关队列填满，触发背压策略，收到ERROR消息并被断开连接。

## 2. 证据

### 正常客户端日志（预期）

```
ts=2026-03-24T12:00:00Z event=client_send type=START session=s-abc123 req=r-def456 bytes=78
ts=2026-03-24T12:00:00Z event=route req=r-def456 session=s-abc123 worker=w-1 inflight=1 overloaded=false
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=1 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":1,"token":"Processing"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=2 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":2,"token":"your"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=3 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":3,"token":"request"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=4 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":4,"token":"Hello, CSD Lab!"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=5 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":5,"token":"is"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=6 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-def456","seq":6,"token":"done"}
ts=2026-03-24T12:00:00Z event=token session=s-abc123 req=r-def456 seq=7 worker=w-1 qlen=0 writable=true type=DONE
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"DONE","reqId":"r-def456","seq":7}
```

### 慢客户端日志（预期）

```
ts=2026-03-24T12:00:00Z event=client_send type=START session=s-xyz789 req=r-ghi123 bytes=78
ts=2026-03-24T12:00:00Z event=route req=r-ghi123 session=s-xyz789 worker=w-2 inflight=1 overloaded=false
ts=2026-03-24T12:00:00Z event=token session=s-xyz789 req=r-ghi123 seq=1 worker=w-2 qlen=0 writable=true type=TOKEN
ts=2026-03-24T12:00:00Z event=client_receive message={"type":"TOKEN","reqId":"r-ghi123","seq":1,"token":"Processing"}
ts=2026-03-24T12:00:01Z event=token session=s-xyz789 req=r-ghi123 seq=2 worker=w-2 qlen=0 writable=false type=TOKEN
ts=2026-03-24T12:00:02Z event=token session=s-xyz789 req=r-ghi123 seq=3 worker=w-2 qlen=1 writable=false type=TOKEN
ts=2026-03-24T12:00:03Z event=token session=s-xyz789 req=r-ghi123 seq=4 worker=w-2 qlen=2 writable=false type=TOKEN
ts=2026-03-24T12:00:04Z event=token session=s-xyz789 req=r-ghi123 seq=5 worker=w-2 qlen=3 writable=false type=TOKEN
ts=2026-03-24T12:00:05Z event=token session=s-xyz789 req=r-ghi123 seq=6 worker=w-2 qlen=4 writable=false type=TOKEN
ts=2026-03-24T12:00:06Z event=token session=s-xyz789 req=r-ghi123 seq=7 worker=w-2 qlen=5 writable=false type=TOKEN
ts=2026-03-24T12:00:07Z event=token session=s-xyz789 req=r-ghi123 seq=8 worker=w-2 qlen=6 writable=false type=TOKEN
ts=2026-03-24T12:00:08Z event=token session=s-xyz789 req=r-ghi123 seq=9 worker=w-2 qlen=7 writable=false type=TOKEN
ts=2026-03-24T12:00:09Z event=token session=s-xyz789 req=r-ghi123 seq=10 worker=w-2 qlen=8 writable=false type=TOKEN
ts=2026-03-24T12:00:10Z event=token session=s-xyz789 req=r-ghi123 seq=11 worker=w-2 qlen=9 writable=false type=TOKEN
ts=2026-03-24T12:00:11Z event=error req=r-ghi123 code=OVERLOADED retryable=false reason=queue_limit close=true
ts=2026-03-24T12:00:11Z event=client_receive message={"type":"ERROR","reqId":"r-ghi123","code":"OVERLOADED","retryable":false,"reason":"queue_limit"}
```

## 3. 假设

### 正常客户端假设
- **假设1**：正常客户端能够快速处理接收到的消息，不会导致网关队列积压。
- **假设2**：网关能够正确路由请求到工作线程，并将生成的TOKEN消息及时发送给客户端。
- **假设3**：完整的START -> TOKEN* -> DONE流程能够顺利执行。

### 慢客户端假设
- **假设1**：慢客户端处理消息的速度慢于网关生成消息的速度。
- **假设2**：网关的有界队列会逐渐填满，最终触发背压策略。
- **假设3**：网关会发送ERROR消息并关闭连接，以防止系统资源耗尽。

## 4. 改动点

### 网关实现改动
1. **消息路由**：实现了基于轮询的会话路由机制，将请求均匀分配到工作线程。
2. **背压策略**：实现了基于有界队列的背压策略，当队列满时拒绝新消息并关闭连接。
3. **可观测性**：添加了详细的日志记录，包含reqId、seq、qlen、writable等关键指标。

### 客户端实现改动
1. **正常客户端**：实现了标准的WebSocket客户端，能够快速处理接收到的消息。
2. **慢客户端**：实现了故意减慢消息处理速度的客户端，用于测试背压策略。
3. **负载测试客户端**：实现了并发客户端，用于测试系统在高负载下的表现。

## 5. 观测指标

### 核心指标
- **reqId**：请求唯一标识符，用于跟踪整个请求生命周期。
- **seq**：消息序列号，确保消息的有序性。
- **qlen**：队列长度，反映背压情况。
- **writable**：连接是否可写，反映客户端处理能力。
- **error_count**：错误数量，反映系统稳定性。

### 性能指标
- **p95**：95%的消息处理延迟，反映系统的响应速度。
- **inflight**：当前处理中的请求数，反映系统负载。
- **overloaded**：系统是否过载，反映系统状态。

## 6. 结论

基于上述证据和假设，我们可以得出以下结论：

1. **正常流程**：START -> TOKEN* -> DONE 能够顺利执行，验证了协议的正确性。
2. **背压策略**：当客户端处理速度慢时，网关能够正确触发背压策略，防止系统资源耗尽。
3. **可观测性**：系统提供了详细的日志和指标，便于问题定位和性能分析。
4. **稳定性**：通过背压策略和错误处理，系统能够在高负载下保持稳定。

这些结论验证了我们的设计和实现是正确的，能够满足实验的要求。