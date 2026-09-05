/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */



package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection whose peer has begun sending a request and has not finished it in time - what nginx
 * calls {@code client_header_timeout}, Tomcat {@code connectionTimeout} and Node {@code headersTimeout}.
 * <p>
 * A deadline rather than a timeout: nothing the peer sends extends it once it is running, so a request
 * dribbled out a byte at a time runs out of it where an idle timeout never would. It is armed when the
 * connection opens, and again on the first burst of bytes which does not complete a request.
 * <p>
 * <b>It must stand behind a decoder</b>, and that is the whole of its placement: {@code channelRead} means
 * the decoder produced a message, while {@code channelReadComplete} arrives after every read from the
 * socket including the ones which produced nothing, so bytes which are not yet a request are visible there
 * and nowhere else. In front of a decoder every read looks the same and the rule cannot be expressed.
 * <p>
 * What follows from that:
 * <ul>
 *     <li>every request of a keep-alive connection is covered, not only the first;</li>
 *     <li>it is never armed while a response is being written - a response travels the other way and there
 *     are no reads to arm it, so a transfer taking minutes is {@link ResponseDeadlineHandler}'s business;</li>
 *     <li>a quiet keep-alive connection is not touched either, and belongs to {@link IdleConnectionHandler}
 *     until its next request begins;</li>
 *     <li>on a websocket it goes on working after the handshake, where what it bounds is a half-arrived
 *     frame.</li>
 * </ul>
 * What it bounds is the request whole, body included, because the message it waits for is the aggregated
 * one - a server which raises {@code maxContentLength} to take uploads raises this with it.
 * <p>
 * A connection which runs out of time is closed without a word rather than answered {@code 408}: the request
 * never arrived, so there is nothing to answer, and nothing here knows how to render an error anyway.
 */
public class RequestDeadlineHandler extends ChannelInboundHandlerAdapter {
    private final long deadlineMs;

    private ScheduledFuture<?> expiry;
    private boolean messageInBurst;

    /**
     * @param deadlineMs a request has to arrive within, counted from the connection opening or from the
     *                   first burst of bytes which did not complete one.
     */
    public RequestDeadlineHandler(final long deadlineMs) {
        if (deadlineMs < 1) {
            throw new IllegalArgumentException("A deadline which has already passed would close every "
                    + "connection as it arrives: " + deadlineMs);
        }
        this.deadlineMs = deadlineMs;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        // a pipeline built by hand may add this to a channel which is active already
        if (ctx.channel().isActive()) {
            arm(ctx);
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        arm(ctx); // an open connection is one whose first request is on its way, however slowly
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) {
        messageInBurst = true; // read of what it was: this handler waits for a message, not for a request
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        // the burst ends here: what was read either became a message or is still on its way
        if (messageInBurst) {
            messageInBurst = false;
            cancel(); // the request arrived; the peer owes nothing until it starts another
        } else if (expiry == null) {
            arm(ctx); // bytes came which are not a request yet. Not re-armed while one is already running:
            // that is what makes it a deadline rather than a timeout
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        cancel();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        cancel(); // one place to stop the clock: a handler taken out of a live pipeline must not leave a
        // task which closes the channel it no longer watches
    }

    private void arm(final ChannelHandlerContext ctx) {
        if (expiry != null) {
            return; // already running, and a deadline which is re-armed by what the peer does is a timeout
        }
        expiry = ctx.executor().schedule(
                (Runnable) ctx::close, // ctx::close fits Callable too, and the two overloads are ambiguous
                deadlineMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancel() {
        if (expiry != null) {
            expiry.cancel(false);
            expiry = null;
        }
    }
}
