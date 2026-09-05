/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */



package io.github.green4j.newa.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection whose peer has stopped taking what was written to it, or is taking it slower than this
 * server is willing to wait.
 * <p>
 * The other half of {@link RequestDeadlineHandler}, and there for the same reason: a peer taking a byte
 * every ten seconds moves the outbound buffer every ten seconds, so {@link IdleConnectionHandler} sees a
 * connection hard at work and holds it, along with whatever the response holds - a rendered buffer, a
 * cursor, an open file. Under load that is a leak a client can start on purpose.
 * <p>
 * <b>The clock exists only while something is owed to the peer.</b> A write starts it and the last write to
 * complete stops it, so a server with nothing to send is never on one: a chunked response ticking once a
 * minute, a suspended cursor, a websocket session with nothing to broadcast owe nothing between messages.
 * <p>
 * <b>What is owed is judged per write, at a rate</b> - one window for every {@link #DEFAULT_UNIT} of a
 * write, counted from the moment it became the oldest one outstanding:
 * <ul>
 *     <li>a chunk, a frame or a small response is one window;</li>
 *     <li>a large response written as one message gets the window its size deserves - a {@link ByteBuf}
 *     cannot be watched draining from outside the channel, so an honest slow peer finishes and a peer taking
 *     a byte at a time does not;</li>
 *     <li>a {@link FileRegion} reports its own progress, so it renews its window every unit which reaches
 *     the peer instead of being judged whole. A trickle is caught within one window however large the file
 *     is.</li>
 * </ul>
 * The unit and the window together are a floor on throughput - 64K per 30 seconds, about 2.2 KB/s - and the
 * same floor for every form of response.
 * <p>
 * It belongs directly behind the aggregator, in the same slot as {@link RequestDeadlineHandler} and in front
 * of everything which answers: it has to see every message on its way out, before the codec turns it into
 * bytes. It measures the payload rather than the encoded frame - a response head, a chunk terminator, a
 * frame mask are not counted - which is a rounding error against a 64K unit.
 * <p>
 * A {@link #RESPONSE_STALLED} event is fired down the pipeline before the close, so a handler which knows
 * what it was writing can report that the response was given up on rather than let it look like an ordinary
 * disconnect. Nothing is written back: the peer is not reading, which is the whole finding.
 */
public class ResponseDeadlineHandler extends ChannelDuplexHandler implements ChannelFutureListener {
    /**
     * Fired down the pipeline just before a connection is closed for not taking its response, so that
     * whatever was being written can report the outcome it deserves.
     */
    public static final Object RESPONSE_STALLED = new Object() {
        @Override
        public String toString() {
            return "RESPONSE_STALLED";
        }
    };

    /**
     * How much of a response has to reach the peer per window. The size a server of this framework writes in
     * anyway - a response chunk and a file chunk are both 64K - so a response which is being taken at all
     * clears it in one write.
     */
    public static final int DEFAULT_UNIT = 64 * 1024;

    private static final int INITIAL_PENDING = 8;

    /**
     * Enough units that no honest response reaches it, and few enough that the allowance of one write can not
     * overflow the clock it is compared against.
     */
    private static final long MAX_UNITS = 1L << 20;

    /**
     * How often the watch runs inside one window. Two, so that no clock has to be read at all: what a write
     * is given is a number of polls, which is what makes an expiry land within half a poll of the window it
     * was given rather than exactly on it.
     */
    private static final int POLLS_PER_WINDOW = 2;

    private final long windowMs;
    private final long periodMillis;
    private final int unit;

    private long[] sizes = new long[INITIAL_PENDING];
    private Object[] messages = new Object[INITIAL_PENDING];
    private int head;
    private int tail;
    private int pending;

    private int pollsLeft;
    private long transferred;
    private ScheduledFuture<?> scheduled;

    /**
     * @param windowMs one unit of a response has to reach the peer within.
     */
    public ResponseDeadlineHandler(final long windowMs) {
        this(windowMs, DEFAULT_UNIT);
    }

    /**
     * @param windowMs one unit of a response has to reach the peer within.
     * @param unit of progress, {@link #DEFAULT_UNIT} by default. Together with the window it is a floor on
     *             throughput, and it is deliberately the size a response is written in: anything smaller
     *             would judge a peer on one packet.
     */
    public ResponseDeadlineHandler(final long windowMs,
                                   final int unit) {
        if (windowMs < 1) {
            throw new IllegalArgumentException("A window which has already passed would close every "
                    + "connection which is written to: " + windowMs);
        }
        if (unit < 1) {
            throw new IllegalArgumentException("A unit of no bytes would be reached without sending any: "
                    + unit);
        }
        this.windowMs = windowMs;
        this.periodMillis = Math.max(1, windowMs / POLLS_PER_WINDOW);
        this.unit = unit;
    }

    @Override
    public void write(final ChannelHandlerContext ctx,
                      final Object msg,
                      final ChannelPromise promise) {
        if (promise.isVoid()) {
            ctx.write(msg, promise); // nothing can be attached to a void promise, so this write can not be
            // followed. Accounting for it without ever hearing it complete would close a healthy connection
            return;
        }

        enqueue(sizeOf(msg), msg);
        promise.addListener(this);

        if (scheduled == null && pending > 0) {
            begin(ctx); // pending can be zero already: a write on a channel which is going completes at once
        }

        ctx.write(msg, promise);
    }

    @Override
    public void operationComplete(final ChannelFuture future) {
        // writes on one channel complete in the order they were made, so what completed is always the head
        dequeue();
        if (pending == 0) {
            cancel(); // nothing is owed: a peer which has taken everything is not on any clock
            return;
        }
        restart(); // the next write becomes the one being judged, with a window of its own
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        cancel();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        cancel();
    }

    /**
     * @return the window one unit of a response is given.
     */
    public long windowMs() {
        return windowMs;
    }

    private void begin(final ChannelHandlerContext ctx) {
        restart();
        scheduled = ctx.executor().scheduleWithFixedDelay(
                () -> check(ctx),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void restart() {
        transferred = transferredAtHead();
        pollsLeft = allowanceOf(messages[head], sizes[head]);
    }

    private void check(final ChannelHandlerContext ctx) {
        if (pending == 0) {
            cancel(); // a write which completed between two ticks
            return;
        }

        final long moved = transferredAtHead();
        if (moved - transferred >= unit) {
            transferred = moved;
            pollsLeft = POLLS_PER_WINDOW; // a file is the one message which can say how much of it arrived,
            // so it is judged as it goes rather than whole: another unit buys another window
            return;
        }

        if (--pollsLeft > 0) {
            return;
        }

        cancel();
        ctx.fireUserEventTriggered(RESPONSE_STALLED); // said before the close, while whatever was being
        // written is still there to hear it
        ctx.close();
    }

    /**
     * @return how much of the write being judged has reached the peer, for the one kind of message which
     *         knows - zero for every other, which is why they are judged whole.
     */
    private long transferredAtHead() {
        final Object msg = messages[head];
        return msg instanceof FileRegion ? ((FileRegion) msg).transferred() : 0L;
    }

    /**
     * @param msg being written.
     * @param size of it.
     * @return how many polls it may go without progress. A message which reports its own progress gets one
     *         window and renews it a unit at a time, so its size does not enter into it - which is the
     *         difference between catching a trickle inside a gigabyte and waiting a gigabyte for it. One
     *         which does not is judged whole, and then its size is exactly what decides how long it deserves.
     */
    private int allowanceOf(final Object msg,
                            final long size) {
        if (msg instanceof FileRegion) {
            return POLLS_PER_WINDOW;
        }
        final long units = Math.min(Math.max(1L, (size + unit - 1) / unit), MAX_UNITS);
        return (int) Math.min(units * POLLS_PER_WINDOW, Integer.MAX_VALUE);
    }

    private void enqueue(final long size,
                         final Object msg) {
        if (pending == sizes.length) {
            grow();
        }
        sizes[tail] = size;
        messages[tail] = msg;
        tail = (tail + 1) % sizes.length;
        pending++;
    }

    private void dequeue() {
        messages[head] = null; // the channel owns the message from here on, and a queue which held it would
        // keep a released buffer reachable
        head = (head + 1) % sizes.length;
        pending--;
    }

    private void grow() {
        final long[] newSizes = new long[sizes.length * 2];
        final Object[] newMessages = new Object[messages.length * 2];
        for (int i = 0; i < pending; i++) {
            final int from = (head + i) % sizes.length;
            newSizes[i] = sizes[from];
            newMessages[i] = messages[from];
        }
        sizes = newSizes;
        messages = newMessages;
        head = 0;
        tail = pending;
    }

    private void cancel() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    /**
     * @param msg being written.
     * @return what it will cost the peer to take, as far as it can be told from here: the payload, without
     *         whatever the codec behind this handler adds to it.
     */
    private static long sizeOf(final Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        return 0L; // a response head, a marker, anything the codec turns into bytes of its own: small
        // enough that one window is the right allowance for it
    }
}
