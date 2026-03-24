package agent.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoadTestClient {

    private static final String SERVER_URI = "ws://localhost:8080/ws";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONCURRENT_CLIENTS = 10;

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_CLIENTS);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    runClient(clientId);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        System.out.printf("ts=%s event=load_test_complete clients=%d duration_ms=%d%n",
                Instant.now(), CONCURRENT_CLIENTS, endTime - startTime);

        executor.shutdown();
    }

    private static void runClient(int clientId) throws Exception {
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String reqId = "r-" + UUID.randomUUID();
        String prompt = "Load test client " + clientId;

        // 构建START消息
        Map<String, Object> startMessage = Map.of(
                "type", "START",
                "sessionId", sessionId,
                "reqId", reqId,
                "prompt", prompt
        );

        String payload = mapper.writeValueAsString(startMessage);

        // 记录发送日志
        System.out.printf(
                "ts=%s event=client_send type=START session=%s req=%s client_id=%d bytes=%d%n",
                Instant.now(),
                sessionId,
                reqId,
                clientId,
                payload.length()
        );

        // 连接WebSocket服务器
        URI uri = new URI(SERVER_URI);
        EventLoopGroup group = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline pipeline = ch.pipeline();
                     pipeline.addLast(new HttpClientCodec());
                     pipeline.addLast(new HttpObjectAggregator(65536));
                     pipeline.addLast(new WebSocketClientHandler(
                             WebSocketClientHandshakerFactory.newHandshaker(
                                     uri,
                                     WebSocketVersion.V13,
                                     null,
                                     true,
                                     new DefaultHttpHeaders()
                             )
                     ));
                 }
             });

            Channel ch = b.connect(uri.getHost(), uri.getPort()).sync().channel();
            WebSocketClientHandler handler = (WebSocketClientHandler) ch.pipeline().last();
            handler.handshakeFuture().sync();

            // 发送START消息
            ch.writeAndFlush(new TextWebSocketFrame(payload));

            // 等待服务器响应
            Thread.sleep(2000);

        } finally {
            group.shutdownGracefully();
        }
    }
}