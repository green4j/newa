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
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a fan-out does when one session throws. The rest of the sessions are still served, the session
 * which threw is reported and closed the way a failed write is, and no buffer is released twice or
 * left behind.
 */
class BroadcastErrorIsolationTest {
    /**
     * Throws where an observer of an application could: from a stage which runs after the channel has
     * already taken the frame.
     */
    private static final class Observed implements WsApiObserver {
        private final boolean throwOnSent;

        private int sent;
        private int writeFailures;
        private boolean closed;

        private Observed(final boolean throwOnSent) {
            this.throwOnSent = throwOnSent;
        }

        @Override
        public void onFrameSent(final int bytes) {
            sent++;
            if (throwOnSent) {
                throw new IllegalStateException("an observer of the application went wrong");
            }
        }

        @Override
        public void onWriteFailed(final Throwable error) {
            writeFailures++;
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            closed = true;
        }
    }

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<Observed> observers = new ArrayList<>();

    private static ByteBuf text(final String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    /**
     * @param sessions the number of the observers to have ready, one per session about to be opened.
     * @param throwingIndex the session whose observer throws, -1 for none.
     * @return an api whose keep-alive is off, so that nothing but the fan-out reaches a channel.
     */
    private WsApi apiOf(final int sessions,
                        final int throwingIndex) {
        for (int i = 0; i < sessions; i++) {
            observers.add(new Observed(i == throwingIndex));
        }
        final AtomicInteger next = new AtomicInteger();

        return new WsApiBuilder(1)
                .withPingIntervalMs(0)  // an EmbeddedChannel runs its scheduled tasks only when told to,
                .withReadTimeoutMs(0)   // and a keep-alive would be noise in the outbound queue anyway
                .withObservers(() -> observers.get(next.getAndIncrement()))
                .build();
    }

    private List<ClientSession> open(final WsApi api,
                                     final int sessions) {
        final List<ClientSession> result = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final EmbeddedChannel channel = new EmbeddedChannel();
            channels.add(channel);
            result.add(
                    api.newSession(
                            new ClientSessionContext(
                                    api,
                                    null,
                                    channel,
                                    0,
                                    0
                            )
                    )
            );
        }
        return result;
    }

    private void assertGot(final int index,
                           final String expected) {
        final TextWebSocketFrame frame = channels.get(index).readOutbound();
        Assertions.assertNotNull(frame, "session " + index + " was never written to");
        try {
            Assertions.assertEquals(expected, frame.text());
        } finally {
            frame.release();
        }
    }

    /**
     * Reads every frame out of every channel and releases it. A retained duplicate still sitting in an
     * outbound queue is holding a reference of the buffer of the fan-out, and that is the peer's frame,
     * not a leak - so the queues are emptied before anything is said about reference counts.
     */
    private void drain() {
        for (int i = 0; i < channels.size(); i++) {
            Object outbound;
            while ((outbound = channels.get(i).readOutbound()) != null) {
                ((TextWebSocketFrame) outbound).release();
            }
        }
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
        observers.clear();
    }

    @Test
    void shouldReachEverySessionWhenOneOfThemThrows() {
        final WsApi api = apiOf(3, 1);
        final List<ClientSession> sessions = open(api, 3);

        api.broadcastText("hello");

        assertGot(0, "hello");
        assertGot(2, "hello");

        Assertions.assertFalse(sessions.get(0).isClosed());
        Assertions.assertTrue(sessions.get(1).isClosed(), "the session which threw is closed");
        Assertions.assertFalse(sessions.get(2).isClosed());

        Assertions.assertEquals(1, observers.get(1).writeFailures);
        Assertions.assertTrue(observers.get(1).closed);
        Assertions.assertEquals(0, observers.get(0).writeFailures);
        Assertions.assertEquals(0, observers.get(2).writeFailures);
    }

    @Test
    void shouldNotReleaseAFrameTheChannelAlreadyOwns() {
        final WsApi api = apiOf(1, 0);
        open(api, 1);

        api.broadcastText("hello");

        // The observer threw after writeAndFlush, so the frame belongs to the channel: releasing it in
        // the failure path would be the second release and would leave the peer reading freed memory.
        final TextWebSocketFrame written = channels.get(0).readOutbound();
        Assertions.assertNotNull(written);
        try {
            Assertions.assertEquals(1, written.refCnt());
            Assertions.assertEquals("hello", written.text());
        } finally {
            written.release();
        }
    }

    @Test
    void shouldReleaseTheBroadcastBufferWhenEverySessionThrows() {
        final WsApi api = apiOf(2, -1);
        observers.set(0, new Observed(true));
        observers.set(1, new Observed(true));
        open(api, 2);

        final ByteBuf buffer = text("hello");

        api.broadcastTextAndRelease(buffer);

        drain();

        Assertions.assertEquals(0, buffer.refCnt(), "the buffer of the fan-out is released whatever "
                + "happened to the sessions it was going to");
    }

    @Test
    void shouldLeaveTheCallersBufferAloneWhenASessionThrows() {
        final WsApi api = apiOf(2, 0);
        open(api, 2);

        final ByteBuf buffer = text("hello");
        try {
            api.broadcastText(buffer); // the form which leaves the buffer to the caller

            assertGot(1, "hello"); // the session after the one which threw was still served
            drain();

            Assertions.assertEquals(1, buffer.refCnt(), "only the reference of the caller is left");
        } finally {
            buffer.release();
        }
    }
}
