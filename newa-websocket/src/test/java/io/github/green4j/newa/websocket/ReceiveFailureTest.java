/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * What happens when the application throws while handling a frame. A websocket has no response left to
 * render by then, so the whole of what a failure gets is the reporting axis: the cause reaches the session's
 * own observer, as it was thrown, and the peer is told a status rather than left with a disconnect.
 */
class ReceiveFailureTest {
    private static final class Observed implements WsApiObserver {
        private final List<String> stages = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();

        @Override
        public void onSessionOpened(final ClientSession session) {
            stages.add("opened");
        }

        @Override
        public void onReceiveFailed(final Throwable error) {
            stages.add("receiveFailed");
            failures.add(error);
        }

        @Override
        public void onWriteFailed(final Throwable error) {
            stages.add("writeFailed");
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

    private ClientSession newSession(final ClientSessions sessions,
                                     final Receiver.Text receiver,
                                     final EmbeddedChannel channel) {
        channels.add(channel);
        return sessions.newSession(
                new ClientSessionContext(
                        new NoWritingResult(),
                        receiver,
                        null,
                        channel,
                        0 // no pinger
                )
        );
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void shouldReportTheCauseAsItWasThrownAndCloseTheSession() {
        final IllegalStateException boom = new IllegalStateException("Failed to read /etc/secret/db.conf");
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final ClientSession session = newSession(sessions, (s, message, last) -> {
            throw boom;
        }, new EmbeddedChannel());

        session.receive("anything"); // and nothing comes back out of it

        Assertions.assertEquals(List.of("opened", "receiveFailed", "closed"), observer.stages);
        Assertions.assertSame(boom, observer.failures.get(0),
                "reported as it was thrown, not wrapped in whatever carried it");
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void shouldTellThePeerTheServerBroke() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final EmbeddedChannel channel = new EmbeddedChannel();
        final ClientSession session = newSession(sessions, (s, message, last) -> {
            throw new IllegalStateException("Boom");
        }, channel);

        session.receive("anything");

        CloseWebSocketFrame close = null;
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (close == null && outbound instanceof CloseWebSocketFrame) {
                close = (CloseWebSocketFrame) outbound;
                Assertions.assertEquals(
                        WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), close.statusCode());
            }
            ReferenceCountUtil.release(outbound);
        }
        Assertions.assertNotNull(close, "a bare disconnect tells the peer nothing about whose fault it was");
        Assertions.assertFalse(channel.isOpen(), "and the session goes, once the status is out");
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void shouldReportNothingWhenTheReceiverReturnsNormally() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final List<String> received = new ArrayList<>();
        final ClientSession session = newSession(sessions,
                (s, message, last) -> received.add(message.toString()), new EmbeddedChannel());

        session.receive("hello");

        Assertions.assertEquals(List.of("hello"), received);
        Assertions.assertEquals(List.of("opened"), observer.stages);
        Assertions.assertFalse(session.isClosed());
    }

    /**
     * The failure is the last word on this connection, so an observer which fails to hear it does not get to
     * keep the session open either.
     */
    @Test
    void shouldCloseTheSessionEvenWhenTheObserverThrows() {
        final ClientSessions sessions = new ClientSessions(null, () -> new WsApiObserver() {
            @Override
            public void onReceiveFailed(final Throwable error) {
                throw new IllegalStateException("the observer went wrong too");
            }
        });

        final ClientSession session = newSession(sessions, (s, message, last) -> {
            throw new IllegalStateException("Boom");
        }, new EmbeddedChannel());

        session.receive("anything");

        Assertions.assertTrue(session.isClosed());
    }
}
