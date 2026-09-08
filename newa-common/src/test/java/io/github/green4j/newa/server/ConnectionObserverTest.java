/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The connections a server closes on its own and answers nothing for. On the peer's side each is
 * indistinguishable from a server which died; this is what makes them distinguishable on this side.
 */
class ConnectionObserverTest {
    private static final int TIMEOUT_MS = 1000;
    private static final int UNIT = 1024;
    private static final long STEP_MS = 50;

    /**
     * What was reported, and whether the channel was still open when it was - a closed one no longer knows
     * its peer, so an observer told after the close can only say that a connection went, not which.
     */
    private static final class Recorded implements ConnectionObserver {
        private final List<String> events = new ArrayList<>();
        private final List<Boolean> openWhenTold = new ArrayList<>();

        private void told(final String event,
                          final Channel channel) {
            events.add(event);
            openWhenTold.add(channel.isOpen());
        }

        @Override
        public void onConnectionRefused(final Channel channel) {
            told("refused", channel);
        }

        @Override
        public void onIdleTimeout(final Channel channel) {
            told("idle", channel);
        }

        @Override
        public void onRequestDeadlineExpired(final Channel channel) {
            told("request deadline", channel);
        }

        @Override
        public void onResponseStalled(final Channel channel) {
            told("response stalled", channel);
        }

        @Override
        public void onPipelinedRequestRefused(final Channel channel) {
            told("pipelined", channel);
        }
    }

    private final Recorded recorded = new Recorded();

    @Test
    public void aConnectionRefusedByTheLimitIsReported() {
        // the hole this closes: without a memory budget the refusal was a counter and nothing else
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(1, recorded);

        final EmbeddedChannel kept = new EmbeddedChannel(limit);
        Assertions.assertEquals(List.of(), recorded.events, "A connection which was kept was reported");

        final EmbeddedChannel refused = new EmbeddedChannel(limit);

        Assertions.assertEquals(List.of("refused"), recorded.events);
        Assertions.assertFalse(refused.isOpen());

        kept.finishAndReleaseAll();
        refused.finishAndReleaseAll();
    }

    @Test
    public void anIdleConnectionIsReportedBeforeItGoes() {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new IdleConnectionHandler(TIMEOUT_MS, recorded));

        inSteps(channel, TIMEOUT_MS);

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("idle"), recorded.events);
        Assertions.assertEquals(List.of(true), recorded.openWhenTold,
                "Reported after the close, with nothing left to say which peer it was");
    }

    @Test
    public void aRequestWhichRanOutOfTimeIsReported() {
        // the one event no observer of requests can ever report: the request never arrived
        final EmbeddedChannel channel = new EmbeddedChannel(
                new RequestDeadlineHandler(TIMEOUT_MS, recorded));

        channel.advanceTimeBy(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("request deadline"), recorded.events);
        Assertions.assertEquals(List.of(true), recorded.openWhenTold);
    }

    @Test
    public void aResponseNobodyIsTakingIsReported() {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ResponseDeadlineHandler(TIMEOUT_MS, UNIT, recorded));

        channel.write(Unpooled.wrappedBuffer(new byte[16])); // written, never flushed: a peer which stopped

        inSteps(channel, TIMEOUT_MS * 2);

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("response stalled"), recorded.events);
        Assertions.assertEquals(List.of(true), recorded.openWhenTold);

        channel.finishAndReleaseAll();
    }

    @Test
    public void aClientPipeliningPastTheDepthIsReported() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler(recorded));
        final FullHttpRequest first = request();
        final FullHttpRequest held = request();
        final FullHttpRequest refused = request();

        channel.writeInbound(first);
        channel.readInbound();
        channel.writeInbound(held);    // held: one deep is served
        channel.writeInbound(refused); // two is more than this connection holds

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("pipelined"), recorded.events);
        Assertions.assertEquals(List.of(true), recorded.openWhenTold);

        first.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void soIsOneWhosePipelinedRequestWasStrandedByAHandshake() {
        // nothing left in the pipeline could answer it: the encoder went with the upgrade
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler(recorded));
        final FullHttpRequest handshake = request();
        final FullHttpRequest held = request();

        channel.writeInbound(handshake);
        channel.readInbound();
        channel.writeInbound(held);
        channel.writeOutbound(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.SWITCHING_PROTOCOLS
        ));

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("pipelined"), recorded.events);

        handshake.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void aConnectionWhichIsUsedIsReportedForNothing() {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new IdleConnectionHandler(TIMEOUT_MS, recorded),
                new RequestDeadlineHandler(TIMEOUT_MS, recorded),
                new ResponseDeadlineHandler(TIMEOUT_MS, UNIT, recorded));

        for (int i = 0; i < 4; i++) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[8]));
            channel.releaseInbound();
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[8]));
            inSteps(channel, TIMEOUT_MS / 4);
        }

        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(List.of(), recorded.events);

        channel.finishAndReleaseAll();
    }

    @Test
    public void anObserverWhichThrowsDoesNotKeepTheConnectionAlive() {
        // broken metrics must not turn into a server which holds every connection it meant to take back
        final ConnectionObserver throwing = new ConnectionObserver() {
            @Override
            public void onIdleTimeout(final Channel channel) {
                throw new IllegalStateException("Not today");
            }
        };
        final EmbeddedChannel channel = new EmbeddedChannel(
                new IdleConnectionHandler(TIMEOUT_MS, throwing));

        inSteps(channel, TIMEOUT_MS);

        Assertions.assertFalse(channel.isOpen(), "A throwing observer held the connection open");
        Assertions.assertDoesNotThrow(channel::checkException,
                "A throwing observer surfaced as a failure of the channel");
    }

    /**
     * Moves an {@link EmbeddedChannel}'s clock in steps: a task which reschedules itself does not run twice
     * in one pass, and every watch here is one of those.
     *
     * @param channel to advance
     * @param millis to advance it by
     */
    private static void inSteps(final EmbeddedChannel channel,
                                final long millis) {
        for (long passed = 0; passed < millis && channel.isOpen(); passed += STEP_MS) {
            channel.advanceTimeBy(STEP_MS, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
    }

    private static FullHttpRequest request() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }
}
