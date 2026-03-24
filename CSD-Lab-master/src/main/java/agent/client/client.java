package agent.client;

import java.time.Instant;
import java.util.UUID;

public class client {
    /* ========== SECTION: CLIENT DEMO ========== */
    // [INTENT] Minimal CLI that shows START request shape and observability fields.
    public static void main(String[] args) {
        String sessionId = args.length > 0 ? args[0] : "s-01";
        String reqId = "r-" + UUID.randomUUID();

        // [PROTO] START event body over WS text frame JSON.
        String payload = String.format(
                "{\"type\":\"START\",\"sessionId\":\"%s\",\"reqId\":\"%s\",\"prompt\":\"hello\"}",
                sessionId,
                reqId
        );

        // [OBS] Keep key=value style for easy grep/join with server logs.
        System.out.printf(
                "ts=%s event=client_send type=START session=%s req=%s bytes=%d%n",
                Instant.now(),
                sessionId,
                reqId,
                payload.length()
        );

        // [TODO] Replace print with actual Netty WS channel writeAndFlush(TextWebSocketFrame(payload)).
        System.out.println(payload);
    }
}