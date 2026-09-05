/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the two receivers of a session a frame reaches, and what a frame nobody took is answered with.
 * A session takes the types it was given a receiver for, so the type it was given none for is not silently
 * dropped - the peer is told a 1003 rather than left waiting for an answer to a frame which went nowhere.
 */
class ReceiverDispatchTest {
    private static final class Observed implements WsApiObserver {
        private final List<String> stages = new ArrayList<>();

        @Override
        public void onSessionOpened(final ClientSession session) {
            stages.add("opened");
        }

        @Override
        public void onReceiveFailed(final Throwable error) {
            stages.add("receiveFailed");
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            stages.add("closed");
        }
    }

    private static final class NoWritingResult implements WritingResult {
        @Override
        public void onWriteSuccess(final ClientSession session) {
        }

        @Override
        public void onWriteBackPressure(final ClientSession session) {
        }

        @Override
        public void onWriteError(final ClientSession session,
                                 final Throwable error) {
        }
    }

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private ClientSession newSession(final Receiver.Text textReceiver,
                                     final Receiver.Binary binaryReceiver) {
        return newSession(textReceiver, binaryReceiver, null);
    }

    private ClientSession newSession(final Receiver.Text textReceiver,
                                     final Receiver.Binary binaryReceiver,
                                     final WsApiObserver observer) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return new ClientSessions(null, observer == null ? null : () -> observer).newSession(
                new ClientSessionContext(
                        new NoWritingResult(),
                        textReceiver,
                        binaryReceiver,
                        channel,
                        0 // no pinger
                )
        );
    }

    private EmbeddedChannel channelOf(final ClientSession session) {
        return (EmbeddedChannel) session.channel();
    }

    private int closeStatusOf(final ClientSession session) {
        Object outbound;
        int status = -1;
        while ((outbound = channelOf(session).readOutbound()) != null) {
            if (status < 0 && outbound instanceof CloseWebSocketFrame) {
                status = ((CloseWebSocketFrame) outbound).statusCode();
            }
            if (outbound instanceof ByteBuf) {
                ((ByteBuf) outbound).release();
            } else {
                ((io.netty.handler.codec.http.websocketx.WebSocketFrame) outbound).release();
            }
        }
        return status;
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void aBinaryMessageReachesTheOneWhichTakesBinary() {
        final List<String> taken = new ArrayList<>();

        final ClientSession session = newSession(
                null,
                (s, payload, last) -> taken.add(payload.toString(CharsetUtil.UTF_8) + ":" + last));

        final ByteBuf first = Unpooled.copiedBuffer("ab", CharsetUtil.UTF_8);
        final ByteBuf second = Unpooled.copiedBuffer("cd", CharsetUtil.UTF_8);
        try {
            session.receive(first, false);
            session.receive(second, true);
        } finally {
            first.release();
            second.release();
        }

        Assertions.assertEquals(List.of("ab:false", "cd:true"), taken);
        Assertions.assertFalse(session.isClosed());
    }

    @Test
    void aTextMessageReachesTheOneWhichTakesText() {
        final List<String> taken = new ArrayList<>();

        final ClientSession session = newSession(
                (s, message, last) -> taken.add(message.toString()),
                null);

        session.receive("hello");

        Assertions.assertEquals(List.of("hello"), taken);
        Assertions.assertFalse(session.isClosed());
    }

    @Test
    void aBinaryMessageIsRefusedByASessionWhichOnlyTakesText() {
        final ClientSession session = newSession(Receivers.echo(), null);

        final ByteBuf payload = Unpooled.copiedBuffer("bytes", CharsetUtil.UTF_8);
        try {
            session.receive(payload, true);
        } finally {
            payload.release();
        }

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(session));
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void aTextMessageIsRefusedByASessionWhichOnlyTakesBinary() {
        final ClientSession session = newSession(null, (s, payload, last) -> {
        });

        session.receive("hello");

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(session));
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void anApiWhichOnlyEverSendsRefusesBothOfThem() {
        final ClientSession text = newSession(null, null);
        text.receive("hello");

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(text));
        Assertions.assertTrue(text.isClosed());

        final ClientSession binary = newSession(null, null);
        final ByteBuf payload = Unpooled.copiedBuffer("bytes", CharsetUtil.UTF_8);
        try {
            binary.receive(payload, true);
        } finally {
            payload.release();
        }

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(binary));
        Assertions.assertTrue(binary.isClosed());
    }

    /**
     * The binary path reports a failure of the application exactly as the text one does: the observer hears
     * the cause and the peer is told the server broke, not that the frame was of a type nobody takes.
     */
    @Test
    void aReceiverWhichThrowsOnBinaryEndsItsSessionWithA1011() {
        final Observed observer = new Observed();

        final ClientSession session = newSession(null, (s, payload, last) -> {
            throw new IllegalStateException("Boom");
        }, observer);

        final ByteBuf payload = Unpooled.copiedBuffer("bytes", CharsetUtil.UTF_8);
        try {
            session.receive(payload, true);
        } finally {
            payload.release();
        }

        Assertions.assertEquals(List.of("opened", "receiveFailed", "closed"), observer.stages);
        Assertions.assertEquals(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), closeStatusOf(session));
    }
}
