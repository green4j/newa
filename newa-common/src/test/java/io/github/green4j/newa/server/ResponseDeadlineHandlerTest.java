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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AbstractReferenceCounted;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.channels.WritableByteChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ResponseDeadlineHandlerTest {
    private static final int WINDOW_MS = 1000;
    private static final int UNIT = 1024;
    private static final long STEP_MS = 50;

    /**
     * A file which reports whatever progress a test wants it to. Nothing is transferred anywhere: what the
     * handler reads is {@link FileRegion#transferred()}, and that is the whole of what a file tells it.
     */
    private static final class FakeFileRegion extends AbstractReferenceCounted implements FileRegion {
        private final long count;

        private long transferred;

        private FakeFileRegion(final long count) {
            this.count = count;
        }

        private void reached(final long bytes) {
            transferred = bytes;
        }

        @Override
        public long position() {
            return 0;
        }

        @Override
        @Deprecated
        public long transfered() {
            return transferred;
        }

        @Override
        public long transferred() {
            return transferred;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public long transferTo(final WritableByteChannel target,
                               final long position) {
            return 0;
        }

        @Override
        public FileRegion retain() {
            super.retain();
            return this;
        }

        @Override
        public FileRegion retain(final int increment) {
            super.retain(increment);
            return this;
        }

        @Override
        public FileRegion touch() {
            return this;
        }

        @Override
        public FileRegion touch(final Object hint) {
            return this;
        }

        @Override
        protected void deallocate() {
        }
    }

    private final AtomicInteger stalled = new AtomicInteger();

    private EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(
                new ResponseDeadlineHandler(WINDOW_MS, UNIT),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext ctx,
                                                   final Object evt) {
                        if (evt == ResponseDeadlineHandler.RESPONSE_STALLED) {
                            stalled.incrementAndGet();
                        }
                    }
                });
    }

    private static void letTimePass(final EmbeddedChannel channel,
                                    final long millis) {
        // moved in steps because the watch reschedules itself, and a task which does that does not run twice
        // in one pass
        for (long passed = 0; passed < millis && channel.isOpen(); passed += STEP_MS) {
            channel.advanceTimeBy(STEP_MS, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
    }

    private static ByteBuf bytes(final int size) {
        return Unpooled.wrappedBuffer(new byte[size]);
    }

    @Test
    public void nothingIsArmedWhileNothingIsOwed() {
        // the property a long-lived stream depends on: a server with nothing to send is on no clock at all
        final EmbeddedChannel channel = channelWithTheHandler();

        letTimePass(channel, WINDOW_MS * 4);

        Assertions.assertTrue(channel.isOpen(), "a connection which owed nothing was closed");
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(), "a clock was running for nothing");
    }

    @Test
    public void aResponseWhichIsNotTakenIsClosed() {
        final EmbeddedChannel channel = channelWithTheHandler();

        channel.write(bytes(16)); // written and not flushed: the peer has been given something and has not
        // taken it, which is what a stuck peer looks like from inside the pipeline

        letTimePass(channel, WINDOW_MS * 2);

        Assertions.assertFalse(channel.isOpen(), "a response nobody took held the connection");
        Assertions.assertEquals(1, stalled.get(), "the response was given up on without saying so");
    }

    @Test
    public void aResponseWhichLandsIsNotJudged() {
        final EmbeddedChannel channel = channelWithTheHandler();

        channel.write(bytes(16));
        letTimePass(channel, WINDOW_MS / 2);
        channel.flush();

        letTimePass(channel, WINDOW_MS * 4);

        Assertions.assertTrue(channel.isOpen(), "a response which was taken closed the connection");
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(), "the clock outlived what it watched");

        channel.finishAndReleaseAll();
    }

    @Test
    public void oneWindowPerUnitOfWhatWasWritten() {
        // a message too large for one window is not judged by one: the floor is a rate, and a peer taking a
        // large response honestly is taking it at that rate for longer
        final EmbeddedChannel channel = channelWithTheHandler();

        channel.write(bytes(UNIT * 4));

        letTimePass(channel, WINDOW_MS * 3);
        Assertions.assertTrue(channel.isOpen(), "closed before the time its size deserved");

        letTimePass(channel, WINDOW_MS * 2);
        Assertions.assertFalse(channel.isOpen(), "held past the time its size deserved");
    }

    @Test
    public void aFileWhichKeepsMovingIsNotJudged() {
        final EmbeddedChannel channel = channelWithTheHandler();
        final FakeFileRegion file = new FakeFileRegion(UNIT * 1024L);

        channel.write(file);

        for (int window = 1; window <= 6; window++) {
            file.reached(UNIT * (long) window); // a unit per window is the floor, and it is met
            letTimePass(channel, WINDOW_MS * 3 / 4);
        }

        Assertions.assertTrue(channel.isOpen(), "a transfer which was moving was cut off");

        channel.finishAndReleaseAll();
    }

    @Test
    public void aFileTrickledOutIsClosed() {
        // the leak this handler exists for: transferred() moves, so anything asking "did bytes move" sees a
        // healthy transfer and holds the descriptor, the open file and the region for as long as the peer likes
        final EmbeddedChannel channel = channelWithTheHandler();
        final FakeFileRegion file = new FakeFileRegion(UNIT * 1024L);

        channel.write(file);

        for (int tick = 1; tick <= 40 && channel.isOpen(); tick++) {
            file.reached(tick); // a byte at a time
            letTimePass(channel, WINDOW_MS / 4);
        }

        Assertions.assertFalse(channel.isOpen(), "a transfer moving a byte at a time was called progress");
    }
}
