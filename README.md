# Agent Gateway 实验项目

## 项目概述

本项目实现了一个极简的 Agent Gateway（智能体网关）控制面协议，用于处理智能体与客户端之间的通信。项目包含网关服务、客户端实现和相关测试工具，旨在验证协议的正确性和系统的稳定性。

## 项目结构

```
CSD-Lab-master/
├── src/
│   └── main/
│       └── java/
│           ├── agent/
│               ├── client/          # 客户端实现
│               │   ├── client.java              # 原始客户端
│               │   ├── NormalClient.java        # 正常客户端
│               │   ├── SlowClient.java          # 慢客户端
│               │   ├── LoadTestClient.java      # 负载测试客户端
│               │   ├── WebSocketClientHandler.java  # WebSocket客户端处理器
│               │   └── SlowWebSocketClientHandler.java  # 慢WebSocket客户端处理器
│               ├── gw/             # 网关实现
│               │   └── Main.java               # 网关主服务
│               └── mock/           # 模拟运行时
│                   └── TokenGen.java           # 令牌生成器
├── PROTOCOL_SPEC.md       # 协议规格文档
├── CONCURRENCY_MODEL.md    # 并发模型图
├── EVIDENCE_CHAIN.md       # 证据链
├── VERIFICATION_RECORD.md  # 验证记录
├── start-server.bat        # 启动服务脚本
├── run-normal-client.bat   # 运行正常客户端脚本
├── run-slow-client.bat     # 运行慢客户端脚本
├── run-load-test.bat       # 运行负载测试脚本
├── pom.xml                 # Maven配置文件
└── README.md               # 项目说明
```

## 核心功能

1. **协议实现**：实现了START/TOKEN*/DONE/ERROR的最小消息集
2. **会话路由**：基于轮询的会话路由机制，将请求分配到工作线程
3. **背压策略**：基于有界队列的背压策略，防止系统资源耗尽
4. **可观测性**：详细的日志记录和指标收集
5. **并发处理**：使用线程池并发处理多个请求

## 技术栈

- **语言**：Java 17
- **网络框架**：Netty 4.1.96.Final
- **JSON处理**：Jackson 2.15.2
- **构建工具**：Maven 3.8.6

## 使用方法

### 1. 启动网关服务

```bash
./start-server.bat
```

### 2. 运行正常客户端

```bash
./run-normal-client.bat
```

### 3. 运行慢客户端

```bash
./run-slow-client.bat
```

### 4. 运行负载测试

```bash
./run-load-test.bat
```

## 协议规格

详细的协议规格请参考 [PROTOCOL_SPEC.md](PROTOCOL_SPEC.md) 文件。

## 并发模型

详细的并发模型请参考 [CONCURRENCY_MODEL.md](CONCURRENCY_MODEL.md) 文件。

## 证据链

详细的证据链请参考 [EVIDENCE_CHAIN.md](EVIDENCE_CHAIN.md) 文件。

## 验证记录

详细的验证记录请参考 [VERIFICATION_RECORD.md](VERIFICATION_RECORD.md) 文件。

## AI 辅助开发说明

本项目使用了AI辅助开发，具体范围如下：

### AI 辅助范围
1. **协议规格设计**：使用AI辅助设计协议规格，包括消息定义、流程图和状态机
2. **代码生成**：使用AI生成初始代码框架，包括网关服务、客户端和WebSocket处理器
3. **并发模型设计**：使用AI辅助设计并发模型图
4. **文档编写**：使用AI辅助编写项目文档，包括协议规格、证据链和验证记录

### 人工改动点
1. **代码优化**：对AI生成的代码进行优化，包括背压策略的实现和错误处理
2. **性能测试**：设计并执行性能测试，收集和分析测试数据
3. **文档完善**：对AI生成的文档进行完善和修正，确保内容准确完整
4. **脚本创建**：创建启动和测试脚本，方便项目运行和验证

## 验收标准

本项目满足以下验收标准：

1. **正常流程**：START -> TOKEN* -> DONE 能够顺利执行
2. **慢客户端场景**：能够稳定复现慢客户端场景，触发ERROR，且错误终止语义明确
3. **可观测性**：结构化日志与关键指标对齐且完整
4. **执行脚本**：4个执行脚本能够重复执行，且输出可信的对比记录

## 总结

本项目实现了一个极简的 Agent Gateway 控制面协议，验证了协议的正确性和系统的稳定性。通过背压策略和线程池管理，系统能够在不同负载下保持稳定，同时提供良好的可观测性。

未来可以考虑进一步优化背压策略，例如根据客户端的历史表现动态调整队列大小，或者实现更精细的流量控制机制，以在系统稳定性和客户端体验之间取得更好的平衡。