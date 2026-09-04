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
 * Closes a connection whose peer has stopped taking what has been written to it, or is taking it slower than
 * a server is willing to wait.
 * <p>
 * This is the other half of {@link RequestDeadlineHandler}, and it exists for the same reason: an idle
 * timeout asks whether anything moved. A peer taking a byte every ten seconds moves the outbound buffer every
 * ten seconds, so {@link IdleConnectionHandler} - which watches that buffer make progress - sees a connection
 * hard at work and holds it, along with whatever the response holds: a rendered buffer, a cursor, an open
 * file. Under load that is a resource leak a client can start on purpose.
 * <p>
 * <b>The clock only exists while something is owed to the peer.</b> It is started by a write and stopped the
 * moment every write has completed, so a server which has nothing to send is never on one. That is what makes
 * this safe in front of a long-lived stream: a chunked response ticking once a minute, a suspended cursor, a
 * websocket session waiting for something to broadcast - all of them owe nothing between messages, and
 * nothing is timing them.
 * <p>
 * <b>What is owed is judged per write, at a rate.</b> A write is given one window for every
 * {@link #DEFAULT_UNIT} of it, counted from the moment it became the oldest one outstanding, and that is the
 * whole policy:
 * <ul>
 *     <li>a chunk, a frame or a small response is one window - which is exactly what a chunked response has
 *     always been given here, so nothing about a chunked response changes;</li>
 *     <li>a large response written as one message gets the window its size deserves, because there is no way
 *     to watch a {@link ByteBuf} being drained from outside the channel - an honest slow peer finishes, a
 *     peer taking a byte at a time does not;</li>
 *     <li>a {@link FileRegion} is the one message which reports its own progress, so instead of being judged
 *     whole it renews its window every time another unit of it has reached the peer. A trickle is caught
 *     within one window however large the file is, and a peer reading a gigabyte honestly is never touched.</li>
 * </ul>
 * The unit and the window together are a floor on throughput - 64K per 30 seconds, about 2.2 KB/s - and it is
 * the same floor for every form of response, which is the point: before this, the floor depended on whether
 * the bytes happened to be counted in chunks or in bytes.
 * <p>
 * It belongs directly behind the aggregator, in the same slot as {@link RequestDeadlineHandler} and in front
 * of everything which answers: what it has to see is every message on its way out, before the codec turns it
 * into bytes. It measures the payload rather than the encoded frame - a response head, a chunk's terminator,
 * a websocket frame's mask are not counted - which is a rounding error against a 64K unit.
 * <p>
 * Before closing, a {@link #RESPONSE_STALLED} event is fired down the pipeline, so that a handler which knows
 * what it was writing can say the response was given up on rather than let it look like an ordinary
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

    /**
     * Takes the write into account and starts the clock if it was not running.
     *
     * @param ctx of this handler.
     * @param msg on its way to the peer.
     * @param promise of that write, which is what says it arrived.
     */
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

    /**
     * The oldest outstanding write has completed - writes on one channel complete in the order they were
     * made, so this is always the one at the head.
     *
     * @param future of that write.
     */
    @Override
    public void operationComplete(final ChannelFuture future) {
        dequeue();
        if (pending == 0) {
            cancel(); // nothing is owed: a peer which has taken everything is not on any clock
            return;
        }
        restart(); // the next write becomes the one being judged, with a window of its own
    }

    /**
     * @param ctx of this handler.
     */
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        cancel();
        ctx.fireChannelInactive();
    }

    /**
     * @param ctx of this handler.
     */
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
