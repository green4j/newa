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
 * What the peer is told when this end ends the session. A bare close is a close of the connection and
 * nothing else, which a client reads as a 1006 it can not tell from the network going; a status is what
 * lets it tell a server which went away from a server which refused it, and those are two different
 * things to do next.
 */
class SessionCloseStatusTest {
    private static final int WRITABILITY_FLAG = 1;

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

    private final Observed observer = new Observed();
    private final ClientSessions sessions = new ClientSessions(null, () -> observer);

    private ClientSession newSession() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        return sessions.newSession(
                new ClientSessionContext(
                        new NoWritingResult(),
                        null,
                        null,
                        channel,
                        0 // no pinger
                )
        );
    }

    private EmbeddedChannel channel() {
        return channels.get(channels.size() - 1);
    }

    private static void setWritable(final EmbeddedChannel channel,
                                    final boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(WRITABILITY_FLAG, writable);
        Assertions.assertEquals(writable, channel.isWritable());
    }

    /**
     * @return the status of every close frame the channel has to show, in the order it wrote them.
     */
    private List<Integer> closeStatuses() {
        final List<Integer> statuses = new ArrayList<>();
        Object outbound;
        while ((outbound = channel().readOutbound()) != null) {
            if (outbound instanceof CloseWebSocketFrame) {
                statuses.add(((CloseWebSocketFrame) outbound).statusCode());
            }
            ReferenceCountUtil.release(outbound);
        }
        return statuses;
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void shouldTellThePeerWhichCloseThisIs() {
        final ClientSession session = newSession();

        session.closeWith(WebSocketCloseStatus.POLICY_VIOLATION);

        Assertions.assertEquals(List.of(WebSocketCloseStatus.POLICY_VIOLATION.code()), closeStatuses());
        Assertions.assertFalse(channel().isOpen(), "and the session goes, once the status is out");
        Assertions.assertTrue(session.isClosed());
        Assertions.assertEquals(List.of("opened", "closed"), observer.stages);
    }

    @Test
    void shouldSayItOnce() {
        final ClientSession session = newSession();

        session.closeWith(WebSocketCloseStatus.POLICY_VIOLATION);
        session.closeWith(WebSocketCloseStatus.NORMAL_CLOSURE); // the session has already had the last word

        Assertions.assertEquals(List.of(WebSocketCloseStatus.POLICY_VIOLATION.code()), closeStatuses());
        Assertions.assertTrue(session.isClosed());
        Assertions.assertEquals(List.of("opened", "closed"), observer.stages,
                "the terminal event is owed once per session, whatever was asked of it afterwards");
    }

    @Test
    void shouldSayNothingOnASessionWhichHasEnded() {
        final ClientSession session = newSession();
        session.close();

        session.closeWith(WebSocketCloseStatus.SERVICE_RESTART);

        Assertions.assertEquals(List.of(), closeStatuses(), "there is nothing left to send a status on");
        Assertions.assertTrue(session.isClosed());
        Assertions.assertEquals(List.of("opened", "closed"), observer.stages);
    }

    @Test
    void shouldEndTheSessionOnAStatusWhichCanNotBeSent() {
        final ClientSession session = newSession();

        session.closeWith(WebSocketCloseStatus.ABNORMAL_CLOSURE); // 1006 is what a peer infers from a
        // connection which went, and an endpoint may not send it

        Assertions.assertEquals(List.of(), closeStatuses());
        Assertions.assertTrue(session.isClosed(), "the closing is what was asked for, the saying why "
                + "was the extra");
        Assertions.assertEquals(List.of("opened", "closed"), observer.stages);
    }

    @Test
    void shouldNotWaitForABufferNobodyIsDraining() {
        final ClientSession session = newSession();
        setWritable(channel(), false); // whatever is written now leaves when the peer reads, and a peer
        // which is not reading is exactly the one this session is being closed on

        session.closeWith(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE);

        Assertions.assertEquals(List.of(), closeStatuses());
        Assertions.assertTrue(session.isClosed(), "and it goes now, rather than whenever the buffer moves");
    }
}
