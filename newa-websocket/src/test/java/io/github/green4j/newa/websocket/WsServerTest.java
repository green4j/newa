/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
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

import java.net.Socket;
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
import java.util.concurrent.ExecutionException;

class WsServerTest {
    private static final String HOST = "127.0.0.1";
    private static final String HEALTH_BODY = "behind the websocket";

    private static final class RecordedErrors implements ChannelErrorHandler {
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onError(final Channel channel,
                            final Throwable cause) {
            errors.add(cause);
        }
    }

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
        return new WsApiBuilder(version)
                .withPathPrefix(pathPrefix)
                .withTextReceiver(Receivers.echo())
                .build();
    }

    /**
     * Answers every request the handshake handler passed on, which is how one port serves both.
     *
     * @return a handler of one channel.
     */
    private static ChannelHandler health() {
        return new SimpleChannelInboundHandler<FullHttpRequest>() {
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
        };
    }

    private String echoOnce(final String path,
                            final String sent) throws Exception {
        return echoOnce(path, sent, null, null);
    }

    private String echoOnce(final String path,
                            final String sent,
                            final String headerName,
                            final String headerValue) throws Exception {
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

        final WebSocket.Builder handshake = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder();

        if (headerName != null) {
            handshake.header(headerName, headerValue);
        }

        handshake.buildAsync(URI.create("ws://" + HOST + ":" + server.port() + path), listener)
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

        // a uri the handshake handler does not recognise is passed on rather than refused, and what
        // stands at the end of the pipeline closes the connection - so this fails at once rather than
        // holding a socket open for as long as the client is willing to wait
        final ExecutionException failed = Assertions.assertThrows(
                ExecutionException.class,
                () -> HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                        .newWebSocketBuilder()
                        .buildAsync(
                                URI.create("ws://" + HOST + ":" + server.port() + "/ws/v1"),
                                new WebSocket.Listener() {
                                })
                        .get(10, TimeUnit.SECONDS)
        );

        Assertions.assertNotNull(failed.getCause(), "no cause: " + failed);
    }

    @Test
    public void maxHeaderSizeIsHonoured() throws Exception {
        // the handshake is the one HTTP request a session makes, and a browser's cookies for this origin
        // travel in it - which is what reaches this limit first
        final String big = repeated('a', 10_000);

        server = WsServer.start(0, echoApi("ws", 1));
        Assertions.assertThrows(
                ExecutionException.class,
                () -> echoOnce("/ws/v1", "too big a handshake", "X-Big", big)
        );
        server.close();

        server = WsServer.of(echoApi("ws", 1)).withMaxHeaderSize(32 * 1024).start(0);
        Assertions.assertEquals("room for it", echoOnce("/ws/v1", "room for it", "X-Big", big));
    }

    @Test
    public void maxInitialLineLengthIsHonoured() throws Exception {
        // the handshake path is the api's and is short, so what reaches this limit here is a request the
        // handshake handler passed on - a rest api sharing the port
        final String longPath = "/health/" + repeated('a', 5000);

        server = WsServer.of(echoApi("ws", 1)).withHandler(WsServerTest::health).start(0);
        Assertions.assertEquals(
                HttpResponseStatus.REQUEST_URI_TOO_LONG.code(),
                get(longPath).statusCode()
        );
        server.close();

        server = WsServer.of(echoApi("ws", 1))
                .withHandler(WsServerTest::health)
                .withMaxInitialLineLength(16 * 1024)
                .start(0);

        final HttpResponse<String> answered = get(longPath);
        Assertions.assertEquals(200, answered.statusCode());
        Assertions.assertEquals(HEALTH_BODY, answered.body());
    }

    private HttpResponse<String> get(final String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://" + HOST + ":" + server.port() + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static String repeated(final char of,
                                   final int times) {
        final StringBuilder result = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            result.append(of);
        }
        return result.toString();
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

        final WsApi api = new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(Receivers.echo())
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
        final RecordedErrors errors = new RecordedErrors();

        server = WsServer.of(echoApi("ws", 1))
                .withChannelErrorHandler(errors)
                .withHandler(WsServerTest::health)
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

        // the handler answered, so the one which closes what nothing took never saw the request
        Assertions.assertEquals(List.of(), errors.errors);
    }

    @Test
    public void aRequestWhichIsNotTheHandshakeIsReportedAndClosed() throws Exception {
        final RecordedErrors errors = new RecordedErrors();

        server = WsServer.of(echoApi("ws", 1))
                .withChannelErrorHandler(errors)
                .start(0);

        try (Socket socket = new Socket(HOST, server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(
                    "GET /nope HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // -1 is the close: without it this socket would sit here open, answered by nothing
            Assertions.assertEquals(-1, socket.getInputStream().read(), "the connection was left open");
        }

        Assertions.assertEquals(1, errors.errors.size(), "reported " + errors.errors);

        final NotAHandshakeException reported = Assertions.assertInstanceOf(
                NotAHandshakeException.class, errors.errors.get(0));

        Assertions.assertEquals("GET", reported.method());
        Assertions.assertEquals("/nope", reported.uri());
        // the frames would name Netty's decoders, so there are none to name anything
        Assertions.assertEquals(0, reported.getStackTrace().length);

        // and the handshake path is untouched by any of it
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
