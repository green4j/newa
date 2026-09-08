/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.SingleHttpExchangeHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The same rule as {@code PipeliningTest} in {@code newa-rest}, on the port which also carries the
 * handshake: whatever answers HTTP behind the api handler is served one exchange at a time. No
 * {@code RestApiHandler} here - this module does not depend on that one - so the api on this port is one
 * handler which answers a request the handshake handler passed on.
 * <p>
 * It answers late on purpose: a handler which answers where it stands finishes its exchange inside the same
 * read, and nothing is ever held.
 */
class PipeliningTest {
    private static final String HOST = "127.0.0.1";
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int ANSWER_DELAY_MILLIS = 300;

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static WsApi api() {
        return new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(Receivers.echo())
                .build();
    }

    /**
     * @return the api of this port's HTTP half, answering after the read which brought the request is over.
     */
    private static ChannelHandler slowHttpApi() {
        return new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx,
                                        final FullHttpRequest request) {
                final String uri = request.uri();
                final String body = '"' + uri.substring(uri.lastIndexOf('/') + 1) + '"';
                ctx.executor().schedule(() -> {
                    final FullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII)
                    );
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                            response.content().readableBytes());
                    ctx.writeAndFlush(response);
                }, ANSWER_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            }
        };
    }

    private void startServer() throws InterruptedException {
        server = WsServer.of(api()).withHandler(PipeliningTest::slowHttpApi).start(0);
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket(HOST, server.port());
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return socket;
    }

    private static String request(final String name) {
        return "GET /hello/" + name + " HTTP/1.1\r\nHost: x\r\n\r\n";
    }

    private static String handshake() {
        return "GET /ws/v1 HTTP/1.1\r\n"
                + "Host: x\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + RawWebSocket.KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
    }

    private static void send(final Socket socket,
                             final String... requests) throws IOException {
        final StringBuilder sent = new StringBuilder();
        for (int i = 0; i < requests.length; i++) {
            sent.append(requests[i]);
        }
        final OutputStream out = socket.getOutputStream();
        out.write(sent.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readUntil(final InputStream in,
                                    final String marker) throws IOException {
        final StringBuilder answered = new StringBuilder();
        while (answered.indexOf(marker) < 0) {
            final int b = in.read();
            if (b < 0) {
                break;
            }
            answered.append((char) b);
        }
        return answered.toString();
    }

    private static String readToEnd(final InputStream in) throws IOException {
        final StringBuilder answered = new StringBuilder();
        int b = in.read();
        while (b >= 0) {
            answered.append((char) b);
            b = in.read();
        }
        return answered.toString();
    }

    @Test
    public void theWebsocketPortHasTheExchangeGate() {
        final EmbeddedChannel channel = new EmbeddedChannel(WsServer.of(api()).pipeline());

        Assertions.assertNotNull(channel.pipeline().get(SingleHttpExchangeHandler.class));

        channel.finishAndReleaseAll();
    }

    @Test
    public void aPipelinedRequestIsAnsweredAfterTheOneInFront() throws Exception {
        startServer();

        try (Socket socket = connect()) {
            send(socket, request("first"), request("second"));

            final String answered = readUntil(socket.getInputStream(), "\"second\"");

            final int first = answered.indexOf("\"first\"");
            final int second = answered.indexOf("\"second\"");
            Assertions.assertTrue(first >= 0, "The first answer never came: " + answered);
            Assertions.assertTrue(second > first, "The answers were out of order: " + answered);
        }
    }

    @Test
    public void aThirdPipelinedRequestIsMoreThanTheConnectionWillHold() throws Exception {
        startServer();

        try (Socket socket = connect()) {
            send(socket, request("first"), request("second"), request("third"));

            // whatever was answered before the connection went, the third was never one of them
            final String answered = readToEnd(socket.getInputStream());

            Assertions.assertFalse(answered.contains("\"third\""), answered);
        }
    }

    @Test
    public void aHandshakeBehindARequestWaitsForItsAnswer() throws Exception {
        startServer();

        try (Socket socket = connect()) {
            send(socket, request("first"), handshake());

            final String answered = readUntil(socket.getInputStream(), "101");

            // the upgrade is what would have taken the aggregator and the response encoder away mid-answer
            final int first = answered.indexOf("\"first\"");
            final int upgraded = answered.indexOf("HTTP/1.1 101");
            Assertions.assertTrue(first >= 0, "The first answer never came: " + answered);
            Assertions.assertTrue(upgraded > first, "The handshake was not held: " + answered);
        }
    }
}
