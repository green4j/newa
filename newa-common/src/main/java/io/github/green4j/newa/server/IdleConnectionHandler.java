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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection on which nothing has been read and nothing has been written for a while.
 * <p>
 * Netty's {@link IdleStateHandler} on its own closes nothing - it fires an {@link IdleStateEvent} and
 * leaves the decision to whatever is behind it, and a pipeline with no handler for that event holds the
 * connection exactly as long as it would have without one. This is the half which decides.
 * <p>
 * What it catches is a connection nobody is using: one which opened and never said anything, a keep-alive
 * connection whose client walked away, and a peer which died without a FIN - the last being the one no
 * amount of correct client code prevents. Each of those costs a file descriptor for as long as the peer
 * likes, and this is the only thing which takes it back.
 * <p>
 * What it does <b>not</b> catch is a slow request: a client dribbling a header block a byte at a time is
 * reading and writing all the while, so it is never idle. Bounding that means bounding the time a request
 * may take to arrive, which is a different timer and not this one.
 * <p>
 * Both directions count, which is what makes it safe in front of a long response: a chunked response still
 * being written keeps its own connection alive, and only a connection where <b>neither</b> side has said
 * anything is closed. It closes and reports nothing - the timeout is the caller's own, so its expiry is
 * not news.
 * <p>
 * A write counts while it is still going, not only once it lands. Netty measures write idleness from the
 * moment a write <i>completes</i> unless told otherwise, and one large file to one slow peer is a single
 * write which completes at the end - so a transfer taking longer than the timeout would be cut off in the
 * middle of itself. Progress in the outbound buffer is watched here instead, so a transfer which is moving
 * at all is not idle, and one which has genuinely stopped moving still is.
 * <p>
 * That is why the timeout is halved and the first expiry is let go. Netty delivers the first idle event
 * whatever the outbound buffer is doing, and only suppresses the ones after it once it has a reading to
 * compare against - so a handler which closed on the first would never see the progress it asked to be
 * told about. The first expiry is what takes that reading, the second is what decides, and half the
 * timeout each is what puts the decision back where the caller asked for it.
 * <p>
 * It belongs at the head of the pipeline, in front of any codec: what it measures is traffic, not messages,
 * and a decoder which is still waiting for the rest of one has nothing to hand on.
 * <p>
 * <b>It is the outermost bound, and the coarsest.</b> All it knows is that bytes moved, not what was in
 * them, so it is the wrong place to express a policy about any one response. A module which is sending
 * something knows better and says so where it knows it - {@code newa-rest} gives a chunked response and a
 * file transfer a stall timeout each, counting what actually reached the peer, and those are half this
 * one's default so that they are what decides. Keep this above them. Set below, it becomes what decides
 * instead, and it decides on a worse measurement than the one it overrode.
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
