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
 * Which of the two methods of a {@link Receiver} a frame reaches, and what a frame nobody took is answered
 * with. A receiver takes what it overrides, so the type it did not override is not silently dropped - the
 * peer is told a 1003 rather than left waiting for an answer to a frame which went nowhere.
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

    private ClientSession newSession(final Receiver receiver) {
        return newSession(receiver, null);
    }

    private ClientSession newSession(final Receiver receiver,
                                     final WsApiObserver observer) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return new ClientSessions(null, observer == null ? null : () -> observer).newSession(
                new ClientSessionContext(
                        new NoWritingResult(),
                        receiver,
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

        final ClientSession session = newSession(new Receiver() {
            @Override
            public void binary(final ClientSession s,
                               final ByteBuf payload,
                               final boolean last) {
                taken.add(payload.toString(CharsetUtil.UTF_8) + ":" + last);
            }
        });

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

        final ClientSession session = newSession(Receivers.ofText(
                (s, message) -> taken.add(message.toString())));

        session.receive("hello");

        Assertions.assertEquals(List.of("hello"), taken);
        Assertions.assertFalse(session.isClosed());
    }

    @Test
    void aBinaryMessageIsRefusedByAReceiverWhichOnlyTakesText() {
        final ClientSession session = newSession(Receivers.echo());

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
    void aTextMessageIsRefusedByAReceiverWhichOnlyTakesBinary() {
        final ClientSession session = newSession(new Receiver() {
            @Override
            public void binary(final ClientSession s,
                               final ByteBuf payload,
                               final boolean last) {
            }
        });

        session.receive("hello");

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(session));
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void anApiWhichOnlyEverSendsRefusesBothOfThem() {
        final ClientSession text = newSession(null);
        text.receive("hello");

        Assertions.assertEquals(WebSocketCloseStatus.INVALID_MESSAGE_TYPE.code(), closeStatusOf(text));
        Assertions.assertTrue(text.isClosed());

        final ClientSession binary = newSession(null);
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

        final ClientSession session = newSession(new Receiver() {
            @Override
            public void binary(final ClientSession s,
                               final ByteBuf payload,
                               final boolean last) {
                throw new IllegalStateException("Boom");
            }
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
