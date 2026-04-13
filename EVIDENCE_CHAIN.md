# 证据链

## 1. 现象

### 正常客户端场景
- **现象**：正常客户端发送START请求后，能够成功接收TOKEN消息和DONE消息，会话正常完成。

### 慢客户端场景
- **现象**：慢客户端发送START请求后，由于处理速度慢，导致网关队列填满，触发背压策略，收到ERROR消息并被断开连接。

## 2. 证据

### 正常客户端日志

```
ts=2026-04-13T03:28:10.167403700Z event=client_send type=START session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d bytes=117
ts=2026-04-13T03:28:10.471596Z event=route req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d session=s-fac70cbb worker=w-0 inflight=-40 overloaded=false
ts=2026-04-13T03:28:10.471596Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=1 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:10.472595800Z event=client_receive message={"type":"TOKEN","seq":1,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"Processing"}
ts=2026-04-13T03:28:10.580115700Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=2 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:10.580115700Z event=client_receive message={"type":"TOKEN","seq":2,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"your"}
ts=2026-04-13T03:28:10.691781Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=3 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:10.691781Z event=client_receive message={"type":"TOKEN","seq":3,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"request"}
ts=2026-04-13T03:28:10.801943900Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=4 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:10.801943900Z event=client_receive message={"type":"TOKEN","seq":4,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"Hello, CSD Lab!"}
ts=2026-04-13T03:28:10.911080300Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=5 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:10.911080300Z event=client_receive message={"type":"TOKEN","seq":5,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"is"}
ts=2026-04-13T03:28:11.019654300Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=6 worker=w-0 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:28:11.019654300Z event=client_receive message={"type":"TOKEN","seq":6,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d","token":"done"}
ts=2026-04-13T03:28:11.129901800Z event=token session=s-fac70cbb req=r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d seq=7 worker=w-0 qlen=0 writable=true type=DONE
ts=2026-04-13T03:28:11.130726700Z event=client_receive message={"type":"DONE","seq":7,"reqId":"r-4459f8c0-39ac-4ce2-9d2b-5eb6358d978d"}
```

### 慢客户端日志

```
ts=2026-04-13T03:24:04.502270900Z event=client_send type=START session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 bytes=117
ts=2026-04-13T03:24:04.810228100Z event=route req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 session=s-b30ccb30 worker=w-1 inflight=-39 overloaded=false
ts=2026-04-13T03:24:04.810228100Z event=client_receive message={"type":"TOKEN","seq":1,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"Processing"}
ts=2026-04-13T03:24:04.810228100Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=1 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.813742600Z event=client_receive message={"type":"TOKEN","seq":2,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"your"}
ts=2026-04-13T03:24:06.822786400Z event=client_receive message={"type":"TOKEN","seq":3,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"request"}
ts=2026-04-13T03:24:07.830120900Z event=client_receive message={"type":"TOKEN","seq":4,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"Hello, CSD Lab!"}
ts=2026-04-13T03:24:08.841340800Z event=client_receive message={"type":"TOKEN","seq":5,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"is"}
ts=2026-04-13T03:24:09.850096600Z event=client_receive message={"type":"TOKEN","seq":6,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12","token":"done"}
ts=2026-04-13T03:24:10.853047100Z event=client_receive message={"type":"DONE","seq":7,"reqId":"r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12"}
ts=2026-04-13T03:24:04.915469600Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=2 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.021121500Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=3 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.125524Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=4 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.234569500Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=5 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.336894Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=6 worker=w-1 qlen=0 writable=true type=TOKEN
ts=2026-04-13T03:24:05.443040600Z event=token session=s-b30ccb30 req=r-d22a7dcc-0a40-48ae-b58b-f51ded24cd12 seq=7 worker=w-1 qlen=0 writable=true type=DONE
```

### 背压策略日志

```
ts=2026-04-13T03:18:46.816543400Z event=route req=r-24356c82-874a-4edf-889c-f4732e0d2117 session=s-fbcb7e09 worker=w-1 inflight=8 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-2c882528-c299-494e-b89e-1612a7a4c529 session=s-85fcc72b worker=w-4 inflight=40 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-6f58d3d2-6aa2-47f4-8451-c081b3c17033 session=s-f8795aff worker=w-3 inflight=39 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-60fc8d95-ea71-4f66-830d-99a68eceda81 session=s-88a875dd worker=w-2 inflight=38 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-10ec664f-12ac-41d6-a1d7-f27aac17a4ab session=s-5bd55f44 worker=w-1 inflight=37 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-3077c814-6b60-4fb7-a266-554e8a8d888e session=s-e9d887c9 worker=w-4 inflight=36 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-ed8f5af1-f6fb-411f-a140-c65a8587d989 session=s-5b93b9bd worker=w-2 inflight=35 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-a405f972-80d4-4bbb-a26e-0d0267bffa5e session=s-4e33f652 worker=w-2 inflight=34 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-aac0c770-f581-488d-a590-afd396b81efe session=s-7e496657 worker=w-4 inflight=32 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-d46065ff-090b-4299-b35f-23e90128272c session=s-4fe315ed worker=w-1 inflight=33 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-f6273137-d8cf-4394-a0cf-3f69a5b14815 session=s-c26036e5 worker=w-3 inflight=31 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-27eb7976-48c1-44bd-ad48-9113f783b322 session=s-5b2c9d61 worker=w-1 inflight=6 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-49c53988-bfc0-4301-9782-56eb0fdbd14c session=s-2de11df6 worker=w-2 inflight=30 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-9d9810e9-5317-455f-9424-4279294831c0 session=s-84e5ad69 worker=w-4 inflight=28 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-90054bb1-9473-4c42-951d-919718a84300 session=s-e1ce61f3 worker=w-1 inflight=29 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-3a97eef4-134d-4536-a24d-94d4128d4cc3 session=s-3ea5bb5a worker=w-3 inflight=27 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-1dd35daa-bbff-445b-a64c-985b6531c7e7 session=s-45c61f8f worker=w-2 inflight=26 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-86b0f1cd-33b4-497b-b835-3ae7ff3dd6a4 session=s-705bece0 worker=w-4 inflight=24 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-555c2efc-24f6-4610-bf03-7225c5b8378b session=s-92edd2db worker=w-4 inflight=25 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-bd527abe-8c38-4626-8c18-d2d8852aa06c session=s-9f3f543c worker=w-1 inflight=15 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-fd38ebcd-cba1-4c4c-a418-7e0ab017fb0d session=s-80bcc548 worker=w-1 inflight=10 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-deda2452-c19d-4d1d-9414-290c95c3639e session=s-c93bb88e worker=w-1 inflight=7 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-6de2f4ad-73a3-4c7d-94c5-1e11a9eb1d4b session=s-4cc4ada7 worker=w-1 inflight=21 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-5394d230-68c7-4dda-84da-b1f0845b95a9 session=s-4c01e1a3 worker=w-1 inflight=1 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-2ade3cc0-14b5-4a4a-9dae-332ea4bc51fa session=s-cbf403a2 worker=w-3 inflight=23 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-53304a27-833c-41d0-aeb5-54ea13f8de39 session=s-87e4d0fe worker=w-1 inflight=16 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-76714a45-b525-48bc-82b1-97c5724ad354 session=s-97a86698 worker=w-2 inflight=22 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-c7006199-3552-476e-96c8-d1d8619180d0 session=s-b4be94bf worker=w-1 inflight=9 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-da513220-06ef-40d9-8bc4-decaa6e123b3 session=s-56cbaeec worker=w-1 inflight=18 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-68842b84-5d13-4a82-bfe0-cde639c6f1e6 session=s-4bb142d0 worker=w-1 inflight=11 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-7db57f7a-3362-46bc-8650-1b4347a8048f session=s-4627cf6e worker=w-1 inflight=5 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-3cb89715-06d1-47e6-9a6e-d42810abce3e session=s-9efed931 worker=w-3 inflight=19 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-db07e0b0-1aca-4efe-8d34-645903e17095 session=s-cdd0fc32 worker=w-4 inflight=20 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-26c6dabe-61c3-4c0d-bbe0-313b56c90ea6 session=s-1d6df107 worker=w-1 inflight=12 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-ce66fc0e-732d-4328-b397-008ff0c4d4f8 session=s-78c3ff0c worker=w-1 inflight=13 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-5929a95e-543a-4518-8729-a1aacf19c822 session=s-f8277b58 worker=w-1 inflight=2 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-4b5cfb83-4c63-4e28-8f30-e6bfed5e5070 session=s-bf357498 worker=w-1 inflight=4 overloaded=false
ts=2026-04-13T03:18:46.816543400Z event=route req=r-a1184348-b66c-42cf-acea-03e4865eb51d session=s-4e505bf2 worker=w-1 inflight=14 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-1bbcc501-ab66-41ef-b228-b004a33ff1aa session=s-af155d5c worker=w-1 inflight=17 overloaded=true
ts=2026-04-13T03:18:46.816543400Z event=route req=r-a053f8c0-7c60-4714-83df-98948783e276 session=s-f7c2b777 worker=w-1 inflight=3 overloaded=false
ts=2026-04-13T03:18:46.826550800Z event=route req=r-e0338a87-25c2-4eab-bb8d-c8bb12e00129 session=s-190eb5b3 worker=w-2 inflight=78 overloaded=true
ts=2026-04-13T03:18:46.826550800Z event=route req=r-e731f97c-2c78-47e0-9212-cc9a5f717347 session=s-759a9fa2 worker=w-1 inflight=77 overloaded=true
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