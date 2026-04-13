package agent.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;

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
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class SlowClient {

    private static final String SERVER_URI = "ws://localhost:8080/ws";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_DELAY_MS = 1200L;

    public static void main(String[] args) throws Exception {
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String reqId = "r-" + UUID.randomUUID();
        String prompt = args.length > 0 ? args[0] : "Hello, CSD Lab!";
        long delayMs = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_DELAY_MS;

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
                "ts=%s event=client_send type=START session=%s req=%s bytes=%d slowDelayMs=%d%n",
                Instant.now(),
                sessionId,
                reqId,
                payload.length(),
                delayMs
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

                     WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                             uri,
                             WebSocketVersion.V13,
                             null,
                             true,
                             new DefaultHttpHeaders()
                     );

                     pipeline.addLast(new SlowWebSocketClientHandler(handshaker, delayMs));
                 }
             });

            Channel ch = b.connect(uri.getHost(), uri.getPort()).sync().channel();
            WebSocketClientHandler handler = (WebSocketClientHandler) ch.pipeline().last();
            handler.handshakeFuture().sync();

            // 发送START消息（握手完成后）
            ch.writeAndFlush(new TextWebSocketFrame(payload)).sync();

            // 慢客户端需要更长窗口完成消费。
            ch.closeFuture().await(30000);

        } finally {
            group.shutdownGracefully();
        }
    }
}