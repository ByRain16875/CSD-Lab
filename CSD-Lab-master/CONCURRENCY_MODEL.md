# 并发模型图

```mermaid
flowchart TD
    subgraph Client_Layer[客户端层]
        Client1[客户端1]
        Client2[客户端2]
        ClientN[客户端N]
    end

    subgraph Gateway_Layer[网关层]
        EventLoop[Netty事件循环<br>处理WebSocket连接]
        SessionMap[共享状态<br>会话映射表]
        Backpressure[背压点<br>队列限制检查]
    end

    subgraph Worker_Layer[工作层]
        WorkerPool[线程池<br>4个工作线程]
        Worker1[工作线程1]
        Worker2[工作线程2]
        Worker3[工作线程3]
        Worker4[工作线程4]
    end

    subgraph Queue_Layer[队列层]
        Queue1[有界队列1<br>容量=10]
        Queue2[有界队列2<br>容量=10]
        QueueN[有界队列N<br>容量=10]
    end

    Client1 -->|WebSocket连接| EventLoop
    Client2 -->|WebSocket连接| EventLoop
    ClientN -->|WebSocket连接| EventLoop

    EventLoop -->|路由请求| WorkerPool
    EventLoop -->|维护会话| SessionMap
    EventLoop -->|检查队列状态| Backpressure

    WorkerPool --> Worker1
    WorkerPool --> Worker2
    WorkerPool --> Worker3
    WorkerPool --> Worker4

    Worker1 -->|生成TOKEN| Queue1
    Worker2 -->|生成TOKEN| Queue2
    Worker4 -->|生成TOKEN| QueueN

    Queue1 -->|发送消息| EventLoop
    Queue2 -->|发送消息| EventLoop
    QueueN -->|发送消息| EventLoop

    EventLoop -->|推送消息| Client1
    EventLoop -->|推送消息| Client2
    EventLoop -->|推送消息| ClientN

    %% 标注阻塞风险点
    Worker1 -->|阻塞风险点1| Sleep[模拟处理延迟<br>Thread.sleep]
    EventLoop -->|阻塞风险点2| Write[网络IO写入<br>可能阻塞]

    %% 标注观测点
    WorkerPool -->|观测点1| Metrics1[指标: 活跃线程数]
    Backpressure -->|观测点2| Metrics2[指标: 队列长度/错误率]

    classDef risk fill:#ffcccc,stroke:#ff0000,stroke-width:2px;
    classDef observe fill:#ccffcc,stroke:#00ff00,stroke-width:2px;
    classDef queue fill:#ccccff,stroke:#0000ff,stroke-width:2px;
    classDef pool fill:#ffcc99,stroke:#ff6600,stroke-width:2px;

    class Sleep,Write risk;
    class Metrics1,Metrics2 observe;
    class Queue1,Queue2,QueueN queue;
    class WorkerPool pool;
```

## 并发模型说明

### 1. 核心组件

1. **线程/事件循环**：使用Netty的NioEventLoopGroup，负责处理WebSocket连接、消息接收和发送。

2. **线程池**：固定大小的线程池（4个线程），用于处理业务逻辑，生成TOKEN消息。

3. **有界队列**：为每个会话维护一个有界队列（容量为10），用于存储待发送的TOKEN消息。

4. **共享状态**：会话映射表，用于跟踪所有活跃的会话。

5. **背压点**：当队列达到容量上限时，触发背压策略，拒绝新的TOKEN消息并关闭连接。

### 2. 阻塞风险点

1. **风险点1**：工作线程中的模拟处理延迟（Thread.sleep），可能导致处理速度跟不上消息生成速度。

2. **风险点2**：网络IO写入操作，当客户端接收速度慢时，可能导致EventLoop阻塞。

### 3. 观测点

1. **观测点1**：线程池的活跃线程数，用于监控系统负载。

2. **观测点2**：队列长度和错误率，用于监控背压策略的触发情况。

### 4. 背压策略

当客户端处理速度慢于消息生成速度时：
1. 消息会被加入到有界队列中
2. 当队列达到容量上限时，触发OVERLOADED错误
3. 关闭连接，防止系统资源耗尽

### 5. 数据流

1. 客户端发送START消息到网关
2. 网关路由请求到工作线程
3. 工作线程生成TOKEN消息并加入队列
4. 事件循环从队列中取出消息并发送给客户端
5. 当客户端处理慢时，队列填满，触发背压策略