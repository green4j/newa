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

import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class WsServerTest {
    private static final String HOST = "127.0.0.1";
    private static final String HEALTH_BODY = "behind the websocket";

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static WsApi echoApi(final String pathPrefix,
                                 final int version) {
        return new SimpleWsApiBuilder(version)
                .withPathPrefix(pathPrefix)
                .withReceiver((session, message) -> session.send(message))
                .build();
    }

    private String echoOnce(final String path,
                            final String sent) throws Exception {
        final CompletableFuture<String> received = new CompletableFuture<>();

        final WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(final WebSocket webSocket) {
                webSocket.sendText(sent, true);
                WebSocket.Listener.super.onOpen(webSocket);
            }

            @Override
            public CompletionStage<?> onText(final WebSocket webSocket,
                                             final CharSequence data,
                                             final boolean last) {
                received.complete(data.toString());
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "ok");
                return CompletableFuture.completedFuture(null);
            }
        };

        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://" + HOST + ":" + server.port() + path), listener)
                .get(10, TimeUnit.SECONDS);

        return received.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void oneLinerEchoes() throws Exception {
        server = WsServer.start(0, echoApi("ws", 1));

        Assertions.assertEquals("echo-check", echoOnce("/ws/v1", "echo-check"));
    }

    @Test
    public void thePathIsTheApiSown() throws Exception {
        // WsApiHandler takes the handshake path from the api, so this is the only place it is set
        server = WsServer.start(0, echoApi("api", 2));

        Assertions.assertEquals("hello", echoOnce("/api/v2", "hello"));

        // a uri the handshake handler does not recognise is passed on rather than refused, and with
        // nothing behind it here nobody answers at all - so this hangs rather than failing
        Assertions.assertThrows(
                TimeoutException.class,
                () -> HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                        .newWebSocketBuilder()
                        .buildAsync(
                                URI.create("ws://" + HOST + ":" + server.port() + "/ws/v1"),
                                new WebSocket.Listener() {
                                })
                        .get(2, TimeUnit.SECONDS)
        );
    }

    @Test
    public void echoesWithCompressionEnabled() throws Exception {
        // the JDK client does not negotiate permessage-deflate, so what this catches is a compression
        // handler which breaks a handshake that never asked for it
        server = WsServer.of(echoApi("ws", 1)).withCompression().start(0);

        Assertions.assertEquals("compressed?", echoOnce("/ws/v1", "compressed?"));
    }

    @Test
    public void observersOfTheApiAreReached() throws Exception {
        final List<String> stages = new CopyOnWriteArrayList<>();

        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix("ws")
                .withReceiver((session, message) -> session.send(message))
                .withObservers(() -> new WsApiObserver() {
                    @Override
                    public void onSessionOpened(final ClientSession session) {
                        stages.add("opened");
                    }

                    @Override
                    public void onFrameReceived(final int bytes) {
                        stages.add("received");
                    }
                })
                .build();

        server = WsServer.start(0, api);

        Assertions.assertEquals("observed", echoOnce("/ws/v1", "observed"));

        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline && !stages.contains("received")) {
            Thread.sleep(10L);
        }
        Assertions.assertTrue(stages.contains("opened"), "session not observed: " + stages);
        Assertions.assertTrue(stages.contains("received"), "frame not observed: " + stages);
    }

    @Test
    public void ownHandlerServesHttpOnTheSamePort() throws Exception {
        // what the handshake handler passed on is what reaches here - the composition that puts a REST api
        // on the websocket's port
        server = WsServer.of(echoApi("ws", 1))
                .withHandler(() -> new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx,
                                                final FullHttpRequest request) {
                        final FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1,
                                HttpResponseStatus.OK,
                                Unpooled.copiedBuffer(HEALTH_BODY, StandardCharsets.UTF_8)
                        );
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                                response.content().readableBytes());
                        ctx.writeAndFlush(response);
                    }
                })
                .start(0);

        final HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://" + HOST + ":" + server.port() + "/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        Assertions.assertEquals(200, health.statusCode());
        Assertions.assertEquals(HEALTH_BODY, health.body());

        // and the websocket still works on the same port
        Assertions.assertEquals("still here", echoOnce("/ws/v1", "still here"));
    }

    @Test
    public void startsOnABootstrapOfItsOwn() throws Exception {
        server = WsServer.of(echoApi("ws", 1))
                .start(new NettyServerBuilder().port(0).host(HOST).workerThreads(2));

        Assertions.assertTrue(server.port() > 0);
        Assertions.assertEquals("bootstrapped", echoOnce("/ws/v1", "bootstrapped"));
    }
}
