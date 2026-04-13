# Agent Gateway 实验项目

## 项目概述

本项目实现了一个极简的 Agent Gateway（智能体网关）控制面协议，用于处理智能体与客户端之间的通信。
当前仓库同时保留两套网关实现：

- `agent.gw.Main`：基线实现（初始版本）
- `agent.gw.Gateway`：优化实现（增强背压、并发安全与入口保护）

项目包含网关服务、客户端实现、负载测试工具和指标脚本，用于验证协议正确性与系统稳定性。

## 项目结构

```text
CSD-Lab-master/
├── src/
│   └── main/
│       └── java/
│           └── agent/
│               ├── client/
│               │   ├── client.java
│               │   ├── NormalClient.java
│               │   ├── SlowClient.java
│               │   ├── LoadTestClient.java
│               │   ├── WebSocketClientHandler.java
│               │   └── SlowWebSocketClientHandler.java
│               ├── gw/
│               │   ├── Main.java                 # 基线网关
│               │   └── Gateway.java              # 优化网关
│               └── mock/
│                   └── TokenGen.java
├── scripts/
│   └── calc_metrics.py                           # 指标统计脚本
├── PROTOCOL_SPEC.md
├── CONCURRENCY_MODEL.md
├── EVIDENCE_CHAIN.md
├── EVIDENCE_CHAIN_2.md
├── VERIFICATION_RECORD.md
├── VERIFICATION_RECORD_2.md
├── start-server.bat                              # 启动 Main
├── start-new-server.bat                          # 启动 Gateway
├── run-normal-client.bat
├── run-slow-client.bat
├── run-load-test.bat
├── pom.xml
└── README.md
```

## 核心功能

1. **协议实现**：实现 START/TOKEN*/DONE/ERROR 最小消息集
2. **会话路由**：请求路由到工作线程，支持并发处理
3. **背压策略**：慢客户端场景下的有界队列与过载保护
4. **可观测性**：结构化日志（`route`/`token`/`error`）与指标统计
5. **双版本对比**：支持 Main 与 Gateway 两个版本并行验证

## 技术栈

- **语言**：Java 17
- **网络框架**：Netty 4.1.96.Final
- **JSON处理**：Jackson 2.15.2
- **构建工具**：Maven 3.8.6
- **指标脚本**：Python 3（`scripts/calc_metrics.py`）

## 使用方法

### 1) 编译项目

```powershell
mvn -q -DskipTests compile
```

### 2) 启动网关服务

- 启动基线版本（Main）：

```powershell
.\start-server.bat
```

- 启动优化版本（Gateway）：

```powershell
.\start-new-server.bat
```

### 3) 运行客户端

```powershell
.\run-normal-client.bat
.\run-slow-client.bat
.\run-load-test.bat
```

### 4) 计算验证指标（从 server.log 生成指标表）

```powershell
py -3 .\scripts\calc_metrics.py .\logs\server.log
```

如果日志来自优化网关且队列容量为 32，可显式指定：

```powershell
py -3 .\scripts\calc_metrics.py .\logs\server_new.log --queue-limit 32
```

## 文档索引

- 协议规格：[`PROTOCOL_SPEC.md`](PROTOCOL_SPEC.md)
- 并发模型：[`CONCURRENCY_MODEL.md`](CONCURRENCY_MODEL.md)
- 证据链（基线）：[`EVIDENCE_CHAIN.md`](EVIDENCE_CHAIN.md)
- 证据链（Gateway 优化）：[`EVIDENCE_CHAIN_2.md`](EVIDENCE_CHAIN_2.md)
- 验证记录（基线）：[`VERIFICATION_RECORD.md`](VERIFICATION_RECORD.md)
- 验证记录（Main -> Gateway 改造）：[`VERIFICATION_RECORD_2.md`](VERIFICATION_RECORD_2.md)

## AI 辅助开发说明

本项目使用了 AI 辅助开发，主要包括：

1. **协议规格设计**：消息定义、流程图、状态机草案
2. **代码框架生成**：网关、客户端与处理器基础代码
3. **并发模型辅助**：并发路径与线程模型梳理
4. **文档编写辅助**：证据链、验证记录、说明文档初稿

人工工作重点包括：

1. **代码优化**：背压闭环、错误语义、并发安全改造
2. **性能测试**：压测与慢客户端场景验证
3. **脚本与指标**：执行脚本与日志指标统计落地
4. **文档校验**：对 AI 初稿进行修订和事实对齐

## 验收标准

1. **正常流程**：START -> TOKEN* -> DONE 可稳定执行
2. **慢客户端场景**：可复现慢消费并触发合理背压/错误终态
3. **可观测性**：结构化日志字段与核心指标可对齐
4. **可重复验证**：脚本可重复执行并输出可比对结果

## 总结

本项目实现并保留了 Main（基线）与 Gateway（优化）两套网关实现，便于教学与对比验证。
通过背压策略、线程池治理与结构化日志，系统可在不同负载下保持可观测和可分析。
后续可继续优化动态限流与更细粒度流控，以平衡系统稳定性与客户端体验。
