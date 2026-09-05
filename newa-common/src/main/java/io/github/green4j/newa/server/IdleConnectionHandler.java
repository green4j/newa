/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection on which nothing has been read and nothing has been written for a while.
 * <p>
 * Netty's {@link IdleStateHandler} on its own closes nothing - it fires an {@link IdleStateEvent} and leaves
 * the decision to whatever is behind it, so a pipeline with no handler for that event holds the connection
 * exactly as long as it would have without one. This is the half which decides.
 * <p>
 * What it catches is a connection nobody is using: one which opened and never said anything, a keep-alive
 * connection whose client walked away, and a peer which died without a FIN - the last being the one no
 * amount of correct client code prevents. Each costs a file descriptor for as long as the peer likes, and
 * this is the only thing which takes it back. What it does <b>not</b> catch is a slow request: a client
 * dribbling a header block a byte at a time is reading and writing all the while and is never idle, which
 * is {@link RequestDeadlineHandler}'s half.
 * <p>
 * <b>Both directions count</b>, which is what makes it safe in front of a long response: only a connection
 * where neither side has said anything is closed. A write counts while it is still going rather than once
 * it lands - Netty measures write idleness from the moment a write <i>completes</i> unless told otherwise,
 * and one large file to one slow peer is a single write which completes at the end, so a transfer longer
 * than the timeout would be cut off in the middle of itself. Progress in the outbound buffer is watched
 * instead.
 * <p>
 * That is also why the timeout is halved and the first expiry is let go: Netty delivers the first idle event
 * whatever the outbound buffer is doing and suppresses the ones after it only once it has a reading to
 * compare against, so the first expiry takes that reading and the second decides. The close is silent - the
 * timeout is the caller's own, so its expiry is not news.
 * <p>
 * It belongs at the head of the pipeline, in front of any codec: what it measures is traffic, not messages,
 * and a decoder still waiting for the rest of one has nothing to hand on.
 * <p>
 * <b>It is the outermost bound and the coarsest</b>: all it knows is that bytes moved, not what was in them.
 * {@link RequestDeadlineHandler} and {@link ResponseDeadlineHandler} judge what actually arrived and what
 * actually reached the peer, and default to half this timeout so that they are what decides. Keep this above
 * them - set below, it decides instead, and on a worse measurement than the one it overrode.
 */
public class IdleConnectionHandler extends IdleStateHandler {
    /**
     * @param idleTimeoutMs of silence in both directions, after which the connection is closed.
     */
    public IdleConnectionHandler(final long idleTimeoutMs) {
        super(true, 0L, 0L, Math.max(1L, idleTimeoutMs / 2L), TimeUnit.MILLISECONDS); // true: watch the
        // outbound buffer make progress, rather than wait for a write to complete before calling the
        // connection used. Half, because it takes two expiries to use that - see channelIdle
    }

    @Override
    protected void channelIdle(final ChannelHandlerContext ctx,
                               final IdleStateEvent evt) {
        if (evt.isFirst()) {
            // the first expiry after any activity is delivered whatever the outbound buffer is doing: it is
            // what takes the reading which the next one is compared against. Closing here would be closing
            // without having looked
            return;
        }
        ctx.close();
    }
}
