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
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTestClient {

    private static final String SERVER_URI = "ws://localhost:8080/ws";
    private static final ObjectMapper mapper = new ObjectMapper();

    // Defaults tuned to trigger Main.route overloaded=true more reliably.
    private static final int DEFAULT_CONCURRENT_CLIENTS = 40;
    private static final int DEFAULT_REQUESTS_PER_CLIENT = 3;
    private static final int DEFAULT_HOLD_MS = 4000;

    public static void main(String[] args) throws Exception {
        int concurrentClients = readArg(args, 0, DEFAULT_CONCURRENT_CLIENTS);
        int requestsPerClient = readArg(args, 1, DEFAULT_REQUESTS_PER_CLIENT);
        int holdMs = readArg(args, 2, DEFAULT_HOLD_MS);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentClients);
        CountDownLatch ready = new CountDownLatch(concurrentClients);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrentClients);
        AtomicInteger sendFailures = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrentClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    runClient(clientId, requestsPerClient, holdMs, ready, startGate);
                } catch (Exception e) {
                    sendFailures.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(30, TimeUnit.SECONDS);
        System.out.printf("ts=%s event=load_test_gate_open clients=%d req_per_client=%d hold_ms=%d%n",
                Instant.now(), concurrentClients, requestsPerClient, holdMs);
        startGate.countDown();

        done.await(60, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        System.out.printf("ts=%s event=load_test_complete clients=%d req_per_client=%d total_req=%d failures=%d duration_ms=%d%n",
                Instant.now(), concurrentClients, requestsPerClient, concurrentClients * requestsPerClient,
                sendFailures.get(), endTime - startTime);

        executor.shutdown();
    }

    private static void runClient(
            int clientId,
            int requestsPerClient,
            int holdMs,
            CountDownLatch ready,
            CountDownLatch startGate
    ) throws Exception {
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

            // Wait until most clients are connected, then burst START together.
            ready.countDown();
            startGate.await(10, TimeUnit.SECONDS);

            for (int i = 0; i < requestsPerClient; i++) {
                String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
                String reqId = "r-" + UUID.randomUUID();
                String prompt = "Load test client " + clientId + " req " + i;

                Map<String, Object> startMessage = Map.of(
                        "type", "START",
                        "sessionId", sessionId,
                        "reqId", reqId,
                        "prompt", prompt
                );
                String payload = mapper.writeValueAsString(startMessage);

                System.out.printf(
                        "ts=%s event=client_send type=START session=%s req=%s client_id=%d req_idx=%d bytes=%d%n",
                        Instant.now(), sessionId, reqId, clientId, i, payload.length()
                );

                ChannelFuture cf = ch.writeAndFlush(new TextWebSocketFrame(payload));
                cf.sync();
            }

            // Keep connection alive to maintain pressure while server is processing.
            Thread.sleep(holdMs);
        } finally {
            group.shutdownGracefully();
        }
    }

    private static int readArg(String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}