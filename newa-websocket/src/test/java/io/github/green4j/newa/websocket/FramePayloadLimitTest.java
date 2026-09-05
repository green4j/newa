/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.server.NettyServer;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class FramePayloadLimitTest {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws/v1";
    private static final int LIMIT = 1024;
    private static final long TIMEOUT_MILLIS = 5_000L;

    private static final int TEXT = 0x1;
    private static final int CLOSE = 0x8;

    private static final class RecordedErrors implements ChannelErrorHandler {
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final CountDownLatch reported = new CountDownLatch(1);

        @Override
        public void onError(final Channel channel,
                            final Throwable cause) {
            errors.add(cause);
            reported.countDown();
        }

        /**
         * A frame past the limit is refused by the decoder, which writes the close and ends the connection
         * itself; the exception reaches this handler after that, on the same loop. So a client which has
         * already seen the connection end has not yet seen the report, and asking for it without waiting is
         * asking on whichever side of that the machine happened to be.
         *
         * @return whether anything was reported before the wait ran out.
         * @throws InterruptedException if interrupted while waiting.
         */
        private boolean awaitReport() throws InterruptedException {
            return reported.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
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

    private static byte[] payloadOf(final int length) {
        final byte[] payload = new byte[length];
        Arrays.fill(payload, (byte) 'x');
        return payload;
    }

    @Test
    public void aFrameWithinTheLimitIsAnswered() throws Exception {
        server = WsServer.of(echoApi())
                .withMaxFramePayloadLength(LIMIT)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);
            client.sendText(payloadOf(LIMIT));

            Assertions.assertEquals(TEXT, client.readFrame()[0]);
        }
    }

    @Test
    public void aFramePastItEndsTheConnection() throws Exception {
        final RecordedErrors errors = new RecordedErrors();

        server = WsServer.of(echoApi())
                .withMaxFramePayloadLength(LIMIT)
                .withChannelErrorHandler(errors)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);
            client.sendText(payloadOf(LIMIT + 1));

            final int[] frame = client.readFrame();

            Assertions.assertEquals(CLOSE, frame[0], "the frame was not a close");
            Assertions.assertEquals(
                    WebSocketCloseStatus.MESSAGE_TOO_BIG.code(), frame[1], "the wrong reason was given");
            Assertions.assertTrue(client.awaitClose(), "the connection was left open");
        }

        Assertions.assertTrue(errors.awaitReport(), "the refusal was never reported");
        Assertions.assertEquals(1, errors.errors.size(), "reported " + errors.errors);
        Assertions.assertInstanceOf(CorruptedWebSocketFrameException.class, errors.errors.get(0));
    }

    @Test
    public void thePayloadIsNotBoundedByWhatTheHandshakeMayBe() throws Exception {
        // withMaxContentLength is the aggregator's, and the aggregator sees exactly one request per
        // connection: the handshake. Nothing after it goes through one
        server = WsServer.of(echoApi())
                .withMaxContentLength(512)
                .withMaxFramePayloadLength(LIMIT)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);
            client.sendText(payloadOf(LIMIT));

            Assertions.assertEquals(TEXT, client.readFrame()[0]);
        }
    }

    @Test
    public void theDefaultIsSixtyFourKilobytes() throws Exception {
        server = WsServer.start(echoApi(), 0);

        Assertions.assertEquals(65536, WsServer.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);
            client.sendText(payloadOf(WsServer.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH + 1));

            Assertions.assertEquals(CLOSE, client.readFrame()[0]);
        }
    }

    @Test
    public void aLargerOneIsServedWhenItIsAskedFor() throws Exception {
        server = WsServer.of(echoApi())
                .withMaxFramePayloadLength(256 * 1024)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);
            client.sendText(payloadOf(128 * 1024));

            Assertions.assertEquals(TEXT, client.readFrame()[0]);
        }
    }
}
