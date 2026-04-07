package agent.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import java.time.Instant;

public class SlowWebSocketClientHandler extends WebSocketClientHandler {

    public SlowWebSocketClientHandler(WebSocketClientHandshaker handshaker) {
        super(handshaker);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (!handshaker.isHandshakeComplete()) {
            handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
            System.out.println("Slow WebSocket Client connected!");
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.status() + ")");
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            System.out.printf("ts=%s event=client_receive message=%s%n", Instant.now(), textFrame.text());
            
            // 故意减慢处理速度，模拟慢客户端
            try {
                Thread.sleep(1000); // 每次处理消息后休眠1秒
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (frame instanceof PongWebSocketFrame) {
            System.out.println("WebSocket Client received pong");
        } else if (frame instanceof CloseWebSocketFrame) {
            System.out.println("WebSocket Client received closing");
            ctx.close();
        }
    }
}