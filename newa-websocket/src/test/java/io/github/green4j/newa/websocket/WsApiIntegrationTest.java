package io.github.green4j.newa.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

class WsApiIntegrationTest {
    private static final String HOST = "127.0.0.1";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private static final class NoOpWsApiListener implements WsApiListener {

        @Override
        public void onWriteBackPressure(final ClientSession session) {
            // no-op
        }

        @Override
        public void onSessionOpened(final ClientSession session) {
            // no-op
        }

        @Override
        public void onSessionClosed(final ClientSession session) {
            // no-op
        }
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final WsApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        pipeline.addLast(
                new WsApiHandler(
                        api,
                        (session, message) -> session.send(message),
                        (channel, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final SimpleWsApiBuilder apiBuilder = new SimpleWsApiBuilder(1)
                .withPathPrefix("ws")
                .withPingIntervalMs(0)
                .withListener(new NoOpWsApiListener());
        final WsApi api = apiBuilder.build();

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), api);
                    }
                });

        serverChannel = bootstrap.bind(HOST, 0).sync().channel();
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } finally {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
        }
    }

    private int serverPort() {
        final InetSocketAddress local = (InetSocketAddress) serverChannel.localAddress();
        return local.getPort();
    }

    @Test
    public void testEchoTextFrame() throws Exception {
        final CompletableFuture<String> receivedText = new CompletableFuture<>();

        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        final URI wsUri = URI.create(
                "ws://" + HOST + ':' + serverPort() + "/ws/v1"
        );

        final WebSocket.Listener listener = new WebSocket.Listener() {

            @Override
            public void onOpen(final WebSocket webSocket) {
                webSocket.sendText("echo-check", true);
                WebSocket.Listener.super.onOpen(webSocket);
            }

            @Override
            public CompletionStage<?> onText(final WebSocket webSocket,
                                             final CharSequence data,
                                             final boolean last) {
                receivedText.complete(data.toString());
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "ok");
                return CompletableFuture.completedFuture(null);
            }
        };

        httpClient.newWebSocketBuilder()
                .buildAsync(wsUri, listener)
                .get(10, TimeUnit.SECONDS);

        Assertions.assertEquals(
                "echo-check",
                receivedText.get(10, TimeUnit.SECONDS)
        );
    }
}
