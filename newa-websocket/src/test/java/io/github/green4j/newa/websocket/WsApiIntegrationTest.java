/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

class WsApiIntegrationTest {
    private static final String HOST = "127.0.0.1";

    private static final class Observed implements WsApiObserver {
        private final List<String> stages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onSessionOpened(final ClientSession session) {
            stages.add("opened");
        }

        @Override
        public void onFrameReceived(final int bytes) {
            stages.add("received:" + bytes);
        }

        @Override
        public void onFrameSent(final int bytes) {
            stages.add("sent:" + bytes);
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            stages.add("closed");
        }
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final List<Observed> observers = Collections.synchronizedList(new ArrayList<>());

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
                .withReceiver((session, message) -> session.send(message))
                .withPingIntervalMs(0)
                .withObservers(() -> {
                    final Observed observer = new Observed();
                    observers.add(observer);
                    return observer;
                });
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

        // one session, and the frame it echoed reported in both directions, before it went away
        Assertions.assertEquals(1, observers.size());

        final Observed observer = observers.get(0);
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline
                && !observer.stages.contains("closed")) {
            Thread.sleep(5);
        }

        Assertions.assertEquals(
                List.of("opened", "received:10", "sent:10", "closed"),
                new ArrayList<>(observer.stages)
        );
    }
}
