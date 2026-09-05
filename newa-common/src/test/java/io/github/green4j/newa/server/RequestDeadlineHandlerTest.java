/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */



package io.github.green4j.newa.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

class RequestDeadlineHandlerTest {
    private static final int DEADLINE_MS = 1000;

    /**
     * Stands in for a codec: it produces a message when a request is complete and nothing at all while one is
     * still arriving, which is the only thing the handler under test knows about the protocol above it.
     */
    private static final class LineDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(final ChannelHandlerContext ctx,
                              final ByteBuf in,
                              final List<Object> out) {
            final int end = in.forEachByte(value -> value != '\n');
            if (end < 0) {
                return; // still arriving
            }
            out.add(in.readSlice(end - in.readerIndex() + 1).retain());
        }
    }

    private static EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(new LineDecoder(), new RequestDeadlineHandler(DEADLINE_MS));
    }

    private static void letTimePass(final EmbeddedChannel channel,
                                    final long millis) {
        // an EmbeddedChannel runs a scheduled task only when it is told to, which is what makes this
        // deterministic: no sleeping, and no timer to be flaky about
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
    }

    private static void send(final EmbeddedChannel channel,
                             final String bytes) {
        channel.writeInbound(Unpooled.copiedBuffer(bytes, StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    /**
     * Sends a byte at a time, a quarter of the deadline apart, until the deadline has passed twice over or
     * the connection is closed - which is the peer no idle timeout catches.
     *
     * @param channel to dribble into.
     * @param bytes to send one at a time.
     */
    private static void dribble(final EmbeddedChannel channel,
                                final String bytes) {
        for (int i = 0; i < 8 && channel.isOpen(); i++) {
            send(channel, bytes);
            letTimePass(channel, DEADLINE_MS / 4);
        }
    }

    @Test
    public void aConnectionWhichAsksNothingIsClosed() {
        final EmbeddedChannel channel = channelWithTheHandler();

        letTimePass(channel, DEADLINE_MS);

        Assertions.assertFalse(channel.isOpen(), "the connection was left open");
    }

    @Test
    public void aRequestWhichArrivesInTimeIsNotJudged() {
        final EmbeddedChannel channel = channelWithTheHandler();

        letTimePass(channel, DEADLINE_MS * 3 / 4);
        send(channel, "GET /\n");

        letTimePass(channel, DEADLINE_MS * 4);

        Assertions.assertTrue(channel.isOpen(), "a connection which asked in time was closed");

        channel.finishAndReleaseAll();
    }

    @Test
    public void aRequestDribbledOutIsClosed() {
        // the case no idle timeout catches: the peer is reading and writing all the while, and would be
        // called busy by anything which only asks whether bytes moved
        final EmbeddedChannel channel = channelWithTheHandler();

        dribble(channel, "x");

        Assertions.assertFalse(channel.isOpen(), "a request dribbled out a byte at a time was let through");
    }

    @Test
    public void theDeadlineIsNotExtendedByWhatThePeerSends() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "G");
        letTimePass(channel, DEADLINE_MS * 3 / 4);
        Assertions.assertTrue(channel.isOpen(), "closed before its deadline");

        send(channel, "E"); // more of the same request, which is not an achievement
        letTimePass(channel, DEADLINE_MS / 2);

        Assertions.assertFalse(channel.isOpen(), "the deadline was extended by another byte");
    }

    @Test
    public void aQuietConnectionAfterARequestIsNotArmed() {
        // what makes this safe in front of a long response: the response travels the other way, and nothing
        // read arms a clock which would cut it off
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /\n");
        letTimePass(channel, DEADLINE_MS * 4);

        Assertions.assertTrue(channel.isOpen(), "a connection with nothing to read was closed");

        channel.finishAndReleaseAll();
    }

    @Test
    public void everyRequestOfAKeepAliveConnectionIsCovered() {
        // the second request is judged exactly like the first: nothing takes the handler out once a
        // connection has proved itself
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /\n");
        letTimePass(channel, DEADLINE_MS * 2);
        Assertions.assertTrue(channel.isOpen(), "closed between requests");

        dribble(channel, "y");

        Assertions.assertFalse(channel.isOpen(), "the second request was allowed to dribble in");
    }

    @Test
    public void aClosedConnectionLeavesNoTaskBehind() {
        final EmbeddedChannel channel = channelWithTheHandler();

        channel.close().syncUninterruptibly();

        // -1 is "nothing scheduled": a handler which stopped its clock leaves nothing to fire at a channel
        // it no longer watches
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(),
                "a task was left to fire at a closed channel");
    }
}
