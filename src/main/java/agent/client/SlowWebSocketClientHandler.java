package agent.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import java.time.Instant;

public class SlowWebSocketClientHandler extends WebSocketClientHandler {

    private final long delayMs;

    public SlowWebSocketClientHandler(WebSocketClientHandshaker handshaker) {
        this(handshaker, 1000L);
    }

    public SlowWebSocketClientHandler(WebSocketClientHandshaker handshaker, long delayMs) {
        super(handshaker);
        this.delayMs = delayMs;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) {
        // Keep base handshake flow so handshakeFuture() can be completed correctly.
        if (!handshaker.isHandshakeComplete()) {
            super.channelRead0(ctx, msg);
            return;
        }

        // Delay text-frame processing to emulate a slow consumer.
        if (msg instanceof TextWebSocketFrame && delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        super.channelRead0(ctx, msg);
    }
}