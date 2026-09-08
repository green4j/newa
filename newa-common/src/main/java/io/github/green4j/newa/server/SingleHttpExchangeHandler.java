/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Keeps a connection to one unfinished response at a time, which is what every per-connection estimate of
 * an HTTP server assumes and what the handlers behind it are written for: a {@code RestApiHandler} keeps the
 * chunked body it is writing in a field of its own, and a second response started alongside the first would
 * take that field away from it.
 * <p>
 * HTTP/1.1 lets a client send the next request without waiting for the answer to the previous one, and this
 * serves that to a depth of one. Reads are held while a response is unfinished, so the only request which
 * can arrive during one is a request the codec had already decoded from the same network read: it is kept
 * and replayed once the final response content has been written, in order and without a second exchange
 * ever being open. A further request on top of that one is more than this handler will hold, and closes the
 * connection - which is what a pipelining client is required to be ready for.
 * <p>
 * It goes <b>directly behind the aggregator</b>, so that what it counts is whole requests and whole
 * responses, and in front of everything which answers - including, on a port which also serves websocket
 * handshakes, in front of the handshake handler. A handshake arriving while an ordinary response is still
 * being written is held here like any other request; reaching the handshaker instead, it would take the
 * {@code HttpObjectAggregator} out of the pipeline and swap the response encoder for a frame encoder in the
 * middle of that response.
 * <p>
 * <b>A {@code 101} retires it.</b> Once a handshake has been answered the connection has stopped speaking
 * HTTP - the encoder which would write an answer goes with it - so this handler takes itself out of the
 * pipeline rather than replay anything into it. A request pipelined behind that handshake can no longer be
 * answered by anybody, and its connection is closed.
 * <p>
 * Both closes are silent, and both are reported to a {@link ConnectionObserver}: a client which reaches
 * either was written against a depth this server does not serve.
 */
public class SingleHttpExchangeHandler extends ChannelDuplexHandler {
    private final ConnectionObserver observer;

    private boolean exchangeInProgress;
    private boolean finalResponse = true;
    private boolean upgrade;
    private boolean manualReadPending;

    /**
     * The one request this handler owns: taken from the pipeline before anything behind it saw it, so
     * releasing it is this handler's until it is replayed.
     */
    private Object held;

    public SingleHttpExchangeHandler() {
        this(null);
    }

    /**
     * @param observer told before a connection is closed for pipelining deeper than this handler serves, or
     *                 null to say nothing.
     */
    public SingleHttpExchangeHandler(final ConnectionObserver observer) {
        this.observer = observer;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) {
        if (msg instanceof FullHttpRequest) {
            if (exchangeInProgress) {
                if (held == null) {
                    held = msg;
                    return;
                }
                ReferenceCountUtil.release(msg);
                refusedPipelined(ctx);
                ctx.close();
                return;
            }
            exchangeInProgress = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void read(final ChannelHandlerContext ctx) {
        if (exchangeInProgress) {
            if (!ctx.channel().config().isAutoRead()) {
                manualReadPending = true;
            }
        } else {
            ctx.read();
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx,
                      final Object msg,
                      final ChannelPromise promise) {
        if (msg instanceof HttpResponse) {
            final int status = ((HttpResponse) msg).status().code();
            upgrade = status == 101;
            finalResponse = status < 100 || status >= 200 || upgrade;
        }
        final boolean completesExchange =
                exchangeInProgress && finalResponse && msg instanceof LastHttpContent;
        final ChannelPromise completion = completesExchange && promise.isVoid()
                ? ctx.newPromise()
                : promise;
        ctx.write(msg, completion);
        if (completesExchange) {
            completion.addListener(written -> {
                if (!written.isSuccess() || !ctx.channel().isActive()) {
                    ctx.close();
                    return;
                }
                exchangeInProgress = false;
                finalResponse = true;
                if (upgrade) {
                    // nothing behind a handshake is an HTTP exchange any more, so this handler goes - and
                    // takes with it the one request it may be holding, which nothing left in this pipeline
                    // could answer
                    final boolean holding = held != null;
                    if (!holding) {
                        resumeReading(ctx);
                    }
                    ctx.pipeline().remove(this); // handlerRemoved releases what was held
                    if (holding) {
                        refusedPipelined(ctx);
                        ctx.channel().close();
                    }
                    return;
                }
                if (held != null) {
                    final Object next = held;
                    held = null;
                    exchangeInProgress = true;
                    // the pipeline owns it again, and no read is asked for: this exchange is open
                    ctx.fireChannelRead(next);
                    ctx.fireChannelReadComplete();
                    return;
                }
                resumeReading(ctx);
            });
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        releaseHeld();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        releaseHeld();
    }

    /**
     * Reports only: each caller closes the way its own position in the pipeline calls for - one still stands
     * in it, the other has just taken itself out.
     *
     * @param ctx of this handler.
     */
    private void refusedPipelined(final ChannelHandlerContext ctx) {
        Observed.by(observer, told -> told.onPipelinedRequestRefused(ctx.channel()));
    }

    private void resumeReading(final ChannelHandlerContext ctx) {
        if (ctx.channel().config().isAutoRead() || manualReadPending) {
            manualReadPending = false;
            ctx.read();
        }
    }

    private void releaseHeld() {
        if (held != null) {
            ReferenceCountUtil.release(held);
            held = null;
        }
    }
}
