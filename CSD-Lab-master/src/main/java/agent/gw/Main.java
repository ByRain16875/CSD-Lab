// Language: Java
package agent.gw;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    private static final int PORT = 8080;
    private static final int QUEUE_LIMIT = 10;
    private static final int WORKER_THREADS = 4;
    private static final Map<String, Session> sessions = new HashMap<>();
    private static final ExecutorService workerPool = Executors.newFixedThreadPool(WORKER_THREADS);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger inflightRequests = new AtomicInteger(0);

    /* ========== SECTION: ENTRY ========== */
    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 100)
             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast(new HttpServerCodec())
                       .addLast(new HttpObjectAggregator(65536))
                       .addLast(new IdleStateHandler(60, 0, 0))
                       .addLast(new WebSocketServerProtocolHandler("/ws"))
                       .addLast(new GatewayHandler());
                 }
             });

            ChannelFuture f = b.bind(PORT).sync();
            System.out.printf("Gateway server started on port %d%n", PORT);
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            workerPool.shutdown();
        }
    }

    /* ========== SECTION: GATEWAY HANDLER ========== */
    static class GatewayHandler extends io.netty.channel.SimpleChannelInboundHandler<TextWebSocketFrame> {
        private String sessionId;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            try {
                String payload = msg.text();
                Map<String, Object> request = mapper.readValue(payload, Map.class);
                String type = (String) request.get("type");

                if ("START".equals(type)) {
                    handleStart(ctx, request);
                } else {
                    System.out.printf("ts=%s event=error req=%s code=INVALID_REQUEST retryable=false reason=invalid_message_type close=true%n",
                            Instant.now(), request.getOrDefault("reqId", "unknown"));
                    ctx.close();
                }
            } catch (Exception e) {
                System.out.printf("ts=%s event=error req=unknown code=INVALID_REQUEST retryable=false reason=parse_error close=true%n",
                        Instant.now());
                ctx.close();
            }
        }

        private void handleStart(ChannelHandlerContext ctx, Map<String, Object> request) {
            sessionId = (String) request.get("sessionId");
            String reqId = (String) request.get("reqId");
            String prompt = (String) request.get("prompt");

            if (sessionId == null || reqId == null || prompt == null) {
                System.out.printf("ts=%s event=error req=%s code=INVALID_REQUEST retryable=false reason=missing_fields close=true%n",
                        Instant.now(), reqId);
                ctx.close();
                return;
            }

            // 路由到工作线程
            String workerId = "w-" + (inflightRequests.get() % WORKER_THREADS + 1);
            boolean overloaded = inflightRequests.get() > WORKER_THREADS * 2;
            logRoute(reqId, sessionId, workerId, inflightRequests.incrementAndGet(), overloaded);

            // 创建会话
            Session session = new Session(sessionId, workerId, QUEUE_LIMIT, ctx);
            sessions.put(sessionId, session);

            // 提交到工作线程处理
            workerPool.submit(() -> processRequest(session, reqId, prompt));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (sessionId != null) {
                sessions.remove(sessionId);
                inflightRequests.decrementAndGet();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    /* ========== SECTION: STREAM PIPELINE ========== */
    private static void processRequest(Session session, String reqId, String prompt) {
        try {
            // 模拟生成令牌
            String[] tokens = {"Processing", "your", "request", prompt, "is", "done"};
            MockTokenSource src = new MockTokenSource(tokens);
            int seq = 0;

            while (src.hasNext()) {
                seq++;
                Envelope tokenEvt = Envelope.token(reqId, seq, src.next());
                streamToken(session, tokenEvt);
                // 模拟处理延迟
                Thread.sleep(100);
            }

            // 发送DONE消息
            Envelope doneEvt = Envelope.done(reqId, seq + 1);
            streamToken(session, doneEvt);
        } catch (Exception e) {
            emitErrorAndClose(session, reqId, "WORKER_ERROR", true, e.getMessage());
        } finally {
            inflightRequests.decrementAndGet();
        }
    }

    private static void streamToken(Session session, Envelope evt) {
        if (evt.type.equals("TOKEN")) {
            if (!session.writable) {
                // [BACKPRESSURE] Queue when channel is not writable.
                if (session.outbound.size() >= session.queueLimit) {
                    emitErrorAndClose(session, evt.reqId, "OVERLOADED", false, "queue_limit");
                    return;
                }
                session.outbound.add(evt);
            } else {
                flushToWire(session, evt);
            }
        } else {
            flushToWire(session, evt);
        }
    }

    private static void flushToWire(Session session, Envelope evt) {
        try {
            // [OBS] Keep log format stable for evidence-chain parsing.
            System.out.printf(
                    "ts=%s event=token session=%s req=%s seq=%d worker=%s qlen=%d writable=%s type=%s%n",
                    Instant.now(), session.sessionId, evt.reqId, evt.seq, session.workerId,
                    session.outbound.size(), session.writable, evt.type
            );

            // 发送消息到客户端
            Map<String, Object> response = new HashMap<>();
            response.put("type", evt.type);
            response.put("reqId", evt.reqId);
            response.put("seq", evt.seq);
            if (!evt.type.equals("DONE")) {
                response.put("token", evt.token);
            }

            String json = mapper.writeValueAsString(response);
            session.ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            session.writable = false;
            System.out.printf("ts=%s event=error req=%s code=SEND_ERROR retryable=false reason=network_error close=true%n",
                    Instant.now(), evt.reqId);
        }
    }

    private static void emitErrorAndClose(Session s, String reqId, String code, boolean retryable, String msg) {
        // [EDGE] ERROR terminal semantic: send once then close stream.
        System.out.printf(
                "ts=%s event=error req=%s code=%s retryable=%s reason=%s close=true%n",
                Instant.now(), reqId, code, retryable, msg
        );

        try {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("type", "ERROR");
            errorResponse.put("reqId", reqId);
            errorResponse.put("code", code);
            errorResponse.put("retryable", retryable);
            errorResponse.put("reason", msg);

            String json = mapper.writeValueAsString(errorResponse);
            s.ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            // 忽略发送错误
        }

        s.ctx.close();
        sessions.remove(s.sessionId);
    }

    private static void logRoute(String reqId, String sessionId, String workerId, int inflight, boolean overloaded) {
        System.out.printf(
                "ts=%s event=route req=%s session=%s worker=%s inflight=%d overloaded=%s%n",
                Instant.now(), reqId, sessionId, workerId, inflight, overloaded
        );
    }

    /* ========== SECTION: DATA TYPES ========== */
    static class Session {
        final String sessionId;
        final String workerId;
        final int queueLimit;
        final ChannelHandlerContext ctx;
        final Queue<Envelope> outbound = new ArrayDeque<>();
        boolean writable = true;

        Session(String sessionId, String workerId, int queueLimit, ChannelHandlerContext ctx) {
            this.sessionId = sessionId;
            this.workerId = workerId;
            this.queueLimit = queueLimit;
            this.ctx = ctx;
        }
    }

    static class Envelope {
        final String type;
        final String reqId;
        final int seq;
        final String token;

        private Envelope(String type, String reqId, int seq, String token) {
            this.type = type;
            this.reqId = reqId;
            this.seq = seq;
            this.token = token;
        }

        static Envelope token(String reqId, int seq, String token) {
            return new Envelope("TOKEN", reqId, seq, token);
        }

        static Envelope done(String reqId, int seq) {
            return new Envelope("DONE", reqId, seq, "");
        }
    }

    static class MockTokenSource {
        private final String[] tokens;
        private int idx = 0;

        MockTokenSource(String[] tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return idx < tokens.length;
        }

        String next() {
            return tokens[idx++];
        }
    }
}