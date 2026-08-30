package io.github.green4j.newa.rest;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * Gives up on a chunked response whose peer has stopped taking it. Nothing is blocked while that happens -
 * the cursor is simply not stepped - but a cursor which is never stepped again holds whatever it holds for as
 * long as the connection lingers, so at some point the connection has to be the thing that goes.
 * <p>
 * Runs on the channel's event loop and reads {@link ChunkedInput#progress()}: one task per chunked response
 * in flight, firing twice per timeout. Closing the channel is what releases the cursor -
 * {@link io.netty.handler.stream.ChunkedWriteHandler} closes the input it can no longer write.
 */
final class ChunkedResponseWatchdog implements Runnable {
    /**
     * Starts watching, unless the timeout is switched off. Stops by itself once the response is written,
     * fails, or the channel goes away.
     *
     * @param ctx of the handler serving the response
     * @param body being pulled
     * @param completion of the whole chunked write
     * @param timeoutMillis a response may go without getting a chunk out, zero to not watch at all
     */
    static void watch(final ChannelHandlerContext ctx,
                      final ChunkedInput<?> body,
                      final ChannelFuture completion,
                      final int timeoutMillis) {
        if (timeoutMillis < 1) {
            return;
        }
        new ChunkedResponseWatchdog(ctx, body, timeoutMillis).start(completion);
    }

    private final ChannelHandlerContext ctx;
    private final ChunkedInput<?> body;
    private final long timeoutNanos;
    private final long periodMillis;

    private long progress;
    private long progressedAt;
    private ScheduledFuture<?> scheduled;

    private ChunkedResponseWatchdog(final ChannelHandlerContext ctx,
                                    final ChunkedInput<?> body,
                                    final int timeoutMillis) {
        this.ctx = ctx;
        this.body = body;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.periodMillis = Math.max(1, timeoutMillis / 2);
    }

    private void start(final ChannelFuture completion) {
        progressedAt = System.nanoTime();
        scheduled = ctx.executor().scheduleWithFixedDelay(
                this,
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
        completion.addListener((ChannelFutureListener) written -> cancel());
    }

    @Override
    public void run() {
        final long written = body.progress();
        if (written != progress) {
            progress = written;
            progressedAt = System.nanoTime();
            return;
        }
        if (System.nanoTime() - progressedAt < timeoutNanos) {
            return;
        }
        // the peer has not taken a single chunk in all that time and is not going to take the rest either
        cancel();
        if (body instanceof ChunkedResponseBody) {
            // so the outcome says the peer stopped taking it, rather than looking like an ordinary disconnect
            ((ChunkedResponseBody) body).markStalled();
        }
        ctx.close();
    }

    private void cancel() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }
}
