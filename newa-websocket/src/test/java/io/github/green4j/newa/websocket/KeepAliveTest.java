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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The keep-alive pair: the ping which gives a session that only listens something to answer, and the read
 * timeout which closes one whose peer stopped answering.
 *
 * <p>The clock behind the timeout is {@link io.github.green4j.newa.lang.WallClock}, a real one nothing can
 * replace, so the intervals here are short and the waits are real. What is not real is the scheduler: an
 * EmbeddedChannel runs a scheduled task only when it is told to, which is what makes the moment the task
 * fires exact.
 */
class KeepAliveTest {
    private static final int WRITABILITY_FLAG = 1;

    private static final long TIMEOUT_MS = 60;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private ClientSession newSession(final long pingIntervalMs,
                                     final long readTimeoutMs) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        final WsApi api = new SimpleWsApiBuilder(1).build();

        return api.newSession(
                new ClientSessionContext(
                        api,
                        null,
                        channel,
                        pingIntervalMs,
                        readTimeoutMs
                )
        );
    }

    private EmbeddedChannel channel() {
        return channels.get(channels.size() - 1);
    }

    /**
     * Lets enough wall clock time pass for the timeout to be over, then brings the scheduler up to the
     * moment the task is due.
     *
     * @param millis to let pass, on both clocks.
     * @throws InterruptedException if the wait is interrupted.
     */
    private void elapse(final long millis) throws InterruptedException {
        Thread.sleep(millis);
        channel().advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel().runScheduledPendingTasks();
        channel().runPendingTasks();
    }

    private static void setWritable(final EmbeddedChannel channel,
                                    final boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(WRITABILITY_FLAG, writable);
        Assertions.assertEquals(writable, channel.isWritable());
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void shouldCloseASessionWhosePeerSaysNothing() throws InterruptedException {
        final ClientSession session = newSession(0, TIMEOUT_MS);

        elapse(TIMEOUT_MS * 2);

        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void shouldKeepASessionWhoseFrameArrived() throws InterruptedException {
        final ClientSession session = newSession(0, TIMEOUT_MS);

        Thread.sleep(TIMEOUT_MS * 2);
        session.frameArrived(); // a pong is a frame like any other, and is all a session which only
        // listens ever sends back

        channel().advanceTimeBy(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        channel().runScheduledPendingTasks();

        Assertions.assertFalse(session.isClosed());
    }

    @Test
    void shouldCloseASessionUnderBackPressureWhosePeerSaysNothing() throws InterruptedException {
        final ClientSession session = newSession(0, TIMEOUT_MS);
        setWritable(channel(), false); // a peer which stopped reading looks exactly like one which is
        // gone, and is no less gone for it

        elapse(TIMEOUT_MS * 2);

        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void shouldPingAnIdleSessionWithoutClosingIt() throws InterruptedException {
        final ClientSession session = newSession(TIMEOUT_MS, 0);

        elapse(TIMEOUT_MS * 2);

        Assertions.assertFalse(session.isClosed());

        final PingWebSocketFrame ping = channel().readOutbound();
        Assertions.assertNotNull(ping, "an idle session is pinged");
        ping.release();
    }

    @Test
    void shouldScheduleNothingWhenBothAreOff() {
        newSession(0, 0);

        Assertions.assertFalse(channel().hasPendingTasks(), "a session which asked for neither a ping "
                + "nor a timeout carries no timer");
    }

    @Test
    void shouldPingAndTimeOutTogether() throws InterruptedException {
        final ClientSession session = newSession(TIMEOUT_MS, TIMEOUT_MS * 3);

        elapse(TIMEOUT_MS * 2); // long enough for a ping, not long enough for the timeout

        Assertions.assertFalse(session.isClosed());
        final PingWebSocketFrame ping = channel().readOutbound();
        Assertions.assertNotNull(ping);
        ping.release();

        elapse(TIMEOUT_MS * 3); // and now the peer has been silent for longer than it may be

        Assertions.assertTrue(session.isClosed());
    }
}
