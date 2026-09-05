/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.server.NettyServer;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

class HandshakeHardeningTest {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws/v1";
    private static final String ALLOWED = "https://app.example.com";
    private static final String REFUSED = "https://evil.example";

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

    private static WsApi echoApi() {
        return new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(Receivers.echo())
                .build();
    }

    private static String statusLineOf(final String head) {
        final int end = head.indexOf("\r\n");
        return end < 0 ? head : head.substring(0, end);
    }

    @Test
    public void anAllowedOriginIsUpgraded() throws Exception {
        server = WsServer.of(echoApi())
                .withOriginPolicy(OriginPolicy.allowing(ALLOWED))
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(PATH, "Origin: " + ALLOWED);

            Assertions.assertEquals("HTTP/1.1 101 Switching Protocols", statusLineOf(head), head);
        }
    }

    @Test
    public void aRefusedOriginIsAnsweredAndReported() throws Exception {
        final RecordedErrors errors = new RecordedErrors();

        server = WsServer.of(echoApi())
                .withOriginPolicy(OriginPolicy.allowing(ALLOWED))
                .withChannelErrorHandler(errors)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(PATH, "Origin: " + REFUSED);

            Assertions.assertEquals("HTTP/1.1 403 Forbidden", statusLineOf(head), head);
            // it was a handshake, so it is answered rather than dropped - and then it goes
            Assertions.assertTrue(client.awaitClose(), "the connection was left open");
        }

        Assertions.assertEquals(1, errors.errors.size(), "reported " + errors.errors);

        final ForbiddenOriginException reported = Assertions.assertInstanceOf(
                ForbiddenOriginException.class, errors.errors.get(0));

        Assertions.assertEquals(REFUSED, reported.origin());
        // the frames would name Netty's decoders, so there are none to name anything
        Assertions.assertEquals(0, reported.getStackTrace().length);
    }

    @Test
    public void aClientWhichIsNotABrowserStillGetsIn() throws Exception {
        server = WsServer.of(echoApi())
                .withOriginPolicy(OriginPolicy.allowing(ALLOWED))
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(PATH); // no Origin at all

            Assertions.assertEquals("HTTP/1.1 101 Switching Protocols", statusLineOf(head), head);
        }
    }


    @Test
    public void anotherOriginIsRefusedWithNothingSaid() throws Exception {
        final RecordedErrors errors = new RecordedErrors();

        server = WsServer.of(echoApi()) // no policy named: the default is the one which checks
                .withChannelErrorHandler(errors)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(PATH, "Origin: " + REFUSED);

            Assertions.assertEquals("HTTP/1.1 403 Forbidden", statusLineOf(head), head);
            Assertions.assertTrue(client.awaitClose(), "the connection was left open");
        }

        Assertions.assertEquals(1, errors.errors.size(), "reported " + errors.errors);
    }

    @Test
    public void ourOwnPageIsUpgradedWithNothingSaid() throws Exception {
        server = WsServer.start(echoApi(), 0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            // the Host of this request is 127.0.0.1:<port>, so this is a page served by this very server
            final String head = client.handshake(PATH, "Origin: http://" + HOST + ':' + server.port());

            Assertions.assertEquals("HTTP/1.1 101 Switching Protocols", statusLineOf(head), head);
        }
    }



    @Test
    public void thereIsNoPolicyWhichIsNoPolicy() {
        // a nullable configuration must not open the server by being absent
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> WsServer.of(echoApi()).withOriginPolicy(null));
    }

    @Test
    public void aCoHostedApiIsNotJudgedByIt() throws Exception {
        server = WsServer.of(echoApi())
                .withOriginPolicy(OriginPolicy.strictly(ALLOWED)) // the strictest there is
                .withHandler(HealthHandler::new)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            // it asks for the upgrade and carries no Origin, but the path is not the api's - so it is not
            // the handshake, and not this handler's to judge
            final String head = client.handshake("/health");

            Assertions.assertEquals("HTTP/1.1 200 OK", statusLineOf(head), head);
        }
    }

    @Test
    public void withoutCompressionNothingIsNegotiated() throws Exception {
        server = WsServer.start(echoApi(), 0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(
                    PATH, "Sec-WebSocket-Extensions: permessage-deflate");

            Assertions.assertEquals("HTTP/1.1 101 Switching Protocols", statusLineOf(head), head);
            // nothing in the pipeline could inflate a frame, so nothing may say it will
            Assertions.assertFalse(
                    head.toLowerCase(Locale.ROOT).contains("sec-websocket-extensions"), head);
        }
    }

    @Test
    public void withCompressionItIs() throws Exception {
        server = WsServer.of(echoApi())
                .withCompression()
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final String head = client.handshake(
                    PATH, "Sec-WebSocket-Extensions: permessage-deflate");

            Assertions.assertEquals("HTTP/1.1 101 Switching Protocols", statusLineOf(head), head);
            Assertions.assertTrue(
                    head.toLowerCase(Locale.ROOT).contains("permessage-deflate"), head);
        }
    }

    private static final class HealthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(final ChannelHandlerContext ctx,
                                    final FullHttpRequest request) {
            if (!"/health".equals(request.uri())) {
                ctx.fireChannelRead(request.retain());
                return;
            }
            final FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response);
        }
    }
}
