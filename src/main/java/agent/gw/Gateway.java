package agent.gw;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Gateway {

    private static final int PORT = 8080;
    private static final int WORKER_THREADS = 4;
    private static final int WORKER_QUEUE_CAPACITY = 64;
    private static final int MAX_INFLIGHT = 128;
    private static final int QUEUE_LIMIT = 32;
    private static final long TOKEN_INTERVAL_MS = 100L;

    private static final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger inflightRequests = new AtomicInteger(0);
    private static final AtomicInteger workerRoundRobin = new AtomicInteger(0);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final ThreadPoolExecutor workerPool = new ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
            new WorkerThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
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

    static class GatewayHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private String sessionId;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            try {
                Map<String, Object> request = mapper.readValue(msg.text(), Map.class);
                String type = (String) request.get("type");

                if ("START".equals(type)) {
                    handleStart(ctx, request);
                } else {
                    logError("unknown", "INVALID_REQUEST", false, "invalid_message_type");
                    ctx.close();
                }
            } catch (Exception e) {
                logError("unknown", "INVALID_REQUEST", false, "parse_error");
                ctx.close();
            }
        }

        private void handleStart(ChannelHandlerContext ctx, Map<String, Object> request) {
            String reqId = (String) request.get("reqId");
            String newSessionId = (String) request.get("sessionId");
            String prompt = (String) request.get("prompt");

            if (newSessionId == null || reqId == null || prompt == null) {
                logError(String.valueOf(reqId), "INVALID_REQUEST", false, "missing_fields");
                ctx.close();
                return;
            }

            if (inflightRequests.get() >= MAX_INFLIGHT || workerPool.getQueue().remainingCapacity() == 0) {
                emitRawErrorAndClose(ctx, reqId, "OVERLOADED", true, "gateway_busy");
                return;
            }

            String workerId = "w-" + (Math.floorMod(workerRoundRobin.getAndIncrement(), WORKER_THREADS) + 1);
            Session newSession = new Session(newSessionId, workerId, QUEUE_LIMIT, ctx);
            Session existing = sessions.putIfAbsent(newSessionId, newSession);
            if (existing != null) {
                emitRawErrorAndClose(ctx, reqId, "INVALID_REQUEST", false, "duplicate_session_id");
                return;
            }

            sessionId = newSessionId;
            int inflight = inflightRequests.incrementAndGet();
            boolean overloaded = inflight >= MAX_INFLIGHT;
            logRoute(reqId, newSessionId, workerId, inflight, overloaded);

            try {
                workerPool.execute(() -> processRequest(newSession, reqId, prompt));
            } catch (RejectedExecutionException ex) {
                emitErrorAndClose(newSession, reqId, "OVERLOADED", true, "worker_queue_full");
                completeSession(newSession);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (sessionId != null) {
                Session s = sessions.get(sessionId);
                if (s != null) {
                    s.writable = ctx.channel().isWritable();
                    if (s.writable) {
                        flushQueued(s);
                    }
                }
            }
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (sessionId != null) {
                Session s = sessions.get(sessionId);
                if (s != null) {
                    completeSession(s);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static void processRequest(Session session, String reqId, String prompt) {
        try {
            String[] tokens = {"Processing", "your", "request", prompt, "is", "done"};
            session.ctx.executor().execute(() -> streamTokenAt(session, reqId, tokens, 0));
        } catch (Exception e) {
            emitErrorAndClose(session, reqId, "WORKER_ERROR", true, e.getMessage());
            completeSession(session);
        }
    }

    private static void streamTokenAt(Session session, String reqId, String[] tokens, int idx) {
        if (session.released.get()) {
            return;
        }

        if (idx < tokens.length) {
            Envelope tokenEvt = Envelope.token(reqId, idx + 1, tokens[idx]);
            if (!streamToken(session, tokenEvt)) {
                completeSession(session);
                return;
            }
            session.ctx.executor().schedule(
                    () -> streamTokenAt(session, reqId, tokens, idx + 1),
                    TOKEN_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        Envelope doneEvt = Envelope.done(reqId, tokens.length + 1);
        streamToken(session, doneEvt);
        completeSession(session);
    }

    private static boolean streamToken(Session session, Envelope evt) {
        if (session.released.get()) {
            return false;
        }

        if ("TOKEN".equals(evt.type)) {
            if (!session.writable) {
                if (session.outbound.size() >= session.queueLimit) {
                    emitErrorAndClose(session, evt.reqId, "OVERLOADED", false, "queue_limit");
                    return false;
                }
                session.outbound.add(evt);
                return true;
            }
            flushToWire(session, evt);
            return true;
        }

        flushToWire(session, evt);
        return true;
    }

    private static void flushQueued(Session session) {
        if (!session.writable || session.released.get()) {
            return;
        }

        Envelope evt;
        while (session.writable && (evt = session.outbound.poll()) != null) {
            writeFrame(session, evt);
            session.writable = session.ctx.channel().isWritable();
        }
        session.ctx.flush();
    }

    private static void flushToWire(Session session, Envelope evt) {
        if (session.released.get()) {
            return;
        }

        writeFrame(session, evt);
        session.ctx.flush();
    }

    private static void writeFrame(Session session, Envelope evt) {
        try {
            System.out.printf(
                    "ts=%s event=token session=%s req=%s seq=%d worker=%s qlen=%d writable=%s type=%s%n",
                    Instant.now(), session.sessionId, evt.reqId, evt.seq, session.workerId,
                    session.outbound.size(), session.writable, evt.type
            );

            Map<String, Object> response = new HashMap<>();
            response.put("type", evt.type);
            response.put("reqId", evt.reqId);
            response.put("seq", evt.seq);
            if (!"DONE".equals(evt.type)) {
                response.put("token", evt.token);
            }

            String json = mapper.writeValueAsString(response);
            session.ctx.write(new TextWebSocketFrame(json));
            session.writable = session.ctx.channel().isWritable();
        } catch (Exception e) {
            session.writable = false;
            logError(evt.reqId, "SEND_ERROR", false, "network_error");
            emitErrorAndClose(session, evt.reqId, "SEND_ERROR", false, "network_error");
        }
    }

    private static void emitErrorAndClose(Session s, String reqId, String code, boolean retryable, String msg) {
        if (s.released.get()) {
            return;
        }
        s.ctx.executor().execute(() -> {
            if (s.released.get()) {
                return;
            }
            logError(reqId, code, retryable, msg);
            try {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("type", "ERROR");
                errorResponse.put("reqId", reqId);
                errorResponse.put("code", code);
                errorResponse.put("retryable", retryable);
                errorResponse.put("reason", msg);

                String json = mapper.writeValueAsString(errorResponse);
                s.ctx.writeAndFlush(new TextWebSocketFrame(json)).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ignored) {
                s.ctx.close();
            }
        });
    }

    private static void emitRawErrorAndClose(ChannelHandlerContext ctx, String reqId, String code, boolean retryable, String msg) {
        logError(reqId, code, retryable, msg);
        try {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("type", "ERROR");
            errorResponse.put("reqId", reqId);
            errorResponse.put("code", code);
            errorResponse.put("retryable", retryable);
            errorResponse.put("reason", msg);

            String json = mapper.writeValueAsString(errorResponse);
            ctx.writeAndFlush(new TextWebSocketFrame(json)).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception ignored) {
            ctx.close();
        }
    }

    private static void completeSession(Session session) {
        if (session.released.compareAndSet(false, true)) {
            sessions.remove(session.sessionId, session);
            inflightRequests.decrementAndGet();
        }
    }

    private static void logRoute(String reqId, String sessionId, String workerId, int inflight, boolean overloaded) {
        System.out.printf(
                "ts=%s event=route req=%s session=%s worker=%s inflight=%d overloaded=%s%n",
                Instant.now(), reqId, sessionId, workerId, inflight, overloaded
        );
    }

    private static void logError(String reqId, String code, boolean retryable, String msg) {
        System.out.printf(
                "ts=%s event=error req=%s code=%s retryable=%s reason=%s close=true%n",
                Instant.now(), reqId, code, retryable, msg
        );
    }

    static class Session {
        final String sessionId;
        final String workerId;
        final int queueLimit;
        final ChannelHandlerContext ctx;
        final Queue<Envelope> outbound = new ArrayDeque<>();
        final AtomicBoolean released = new AtomicBoolean(false);
        volatile boolean writable;

        Session(String sessionId, String workerId, int queueLimit, ChannelHandlerContext ctx) {
            this.sessionId = sessionId;
            this.workerId = workerId;
            this.queueLimit = queueLimit;
            this.ctx = ctx;
            this.writable = ctx.channel().isWritable();
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

    static class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger idx = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gw-worker-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}

