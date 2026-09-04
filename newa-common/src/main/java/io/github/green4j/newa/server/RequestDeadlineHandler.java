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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection whose peer has begun sending a request and has not finished it in time.
 * <p>
 * This is the half {@link IdleConnectionHandler} cannot be. An idle timeout asks whether anything moved; a
 * client dribbling a header block a byte at a time is moving all the while and is never idle, so it holds a
 * file descriptor for as long as it likes. What has to be bounded instead is <b>the time a request may take
 * to arrive</b>, and that is a deadline rather than a timeout: nothing the peer does extends it once it is
 * running. nginx calls it {@code client_header_timeout}, Tomcat {@code connectionTimeout}, Node
 * {@code headersTimeout}; this is that bound.
 * <p>
 * It knows nothing about the protocol above it, and does not need to. What it needs is to tell one kind of
 * read from another - bytes which became a request from bytes which did not - and the pipeline says which
 * is which, as long as this handler stands <b>behind a decoder</b>:
 * <ul>
 *     <li>{@code channelRead} means the decoder produced something, so a request has arrived;</li>
 *     <li>{@code channelReadComplete} arrives after every read from the socket, <b>including the reads which
 *     produced nothing</b> - Netty's {@code ByteToMessageDecoder} fires it whatever it decoded, and
 *     {@code MessageToMessageDecoder} passes it on - so a burst of bytes which is not yet a request is
 *     visible here and nowhere else.</li>
 * </ul>
 * In front of a decoder every read looks the same and the rule cannot be expressed at all. That is the one
 * placement requirement, and it is why this handler goes directly behind the aggregator rather than at the
 * head of the pipeline where {@link IdleConnectionHandler} and {@link ConnectionLimitHandler} stand.
 * <p>
 * The deadline is armed when the connection opens - a peer which says nothing at all is a peer whose request
 * is not arriving either - and again on the first burst which does not complete one. It is <b>not</b> extended
 * by the bursts that follow: a request which has begun arriving has that long to finish, however many packets
 * it is dribbled in.
 * <p>
 * What follows from expressing it that way:
 * <ul>
 *     <li><b>Every request of a keep-alive connection is covered</b>, not only the first, so nothing has to
 *     take this handler out of the pipeline once a connection has proved itself.</li>
 *     <li><b>It is never armed while a response is being written.</b> A response travels the other way; there
 *     are no reads to arm it, so a chunked response or a file transfer taking minutes is not this handler's
 *     business. {@link ResponseDeadlineHandler} is what judges the peer taking it.</li>
 *     <li><b>A quiet keep-alive connection is not touched either</b>: nothing is being read, so nothing is
 *     armed, and the connection belongs to {@link IdleConnectionHandler} until the next request begins.
 *     Tomcat spends one timeout on both of those; here they are two bounds with two reasons.</li>
 *     <li><b>On a websocket it goes on working after the handshake</b>, where a frame is the message and a
 *     half-arrived frame is what it bounds. A session which merely says nothing is not read from at all, so
 *     it is not armed.</li>
 * </ul>
 * What it bounds is the request whole, body included, because the message it waits for is the aggregated one.
 * A 64K {@code maxContentLength} is what makes one deadline honest for every request; a server which raises
 * that to take uploads raises this with it.
 * <p>
 * <b>A connection which runs out of time is closed without a word</b>, not answered {@code 408}. The request
 * never arrived, so there is nothing to answer, and nothing here knows how to render an error anyway - the
 * same arithmetic {@link ConnectionLimitHandler} closes on.
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

    /**
     * Arms the deadline on a channel which is active already - a pipeline built by hand may add this handler
     * to one.
     *
     * @param ctx of this handler.
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            arm(ctx);
        }
    }

    /**
     * @param ctx of this handler.
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        arm(ctx); // an open connection is one whose first request is on its way, however slowly
        ctx.fireChannelActive();
    }

    /**
     * @param ctx of this handler.
     * @param msg the decoder produced, which is what a request looks like from here.
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) {
        messageInBurst = true; // read of what it was: this handler waits for a message, not for a request
        ctx.fireChannelRead(msg);
    }

    /**
     * Ends the burst: what was read either became a message or is still on its way.
     *
     * @param ctx of this handler.
     */
    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        if (messageInBurst) {
            messageInBurst = false;
            cancel(); // the request arrived; the peer owes nothing until it starts another
        } else if (expiry == null) {
            arm(ctx); // bytes came which are not a request yet. Not re-armed while one is already running:
            // that is what makes it a deadline rather than a timeout
        }
        ctx.fireChannelReadComplete();
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
