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

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

public class RestApiHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RestRouter api;
    private final HttpErrorHandler errorHandler;
    private final ChannelErrorHandler channelErrorHandler;
    private final ResponseChunks responseChunks;
    private final HttpObserverFactory observers;
    private final RestApiObserverFactory restObservers;

    private ChunkedResponseBody stalling;

    public RestApiHandler(final RestRouter restApi,
                          final HttpErrorHandler errorHandler,
                          final ChannelErrorHandler channelErrorHandler) {
        this(
                restApi,
                errorHandler,
                channelErrorHandler,
                ResponseChunks.defaults(),
                null
        );
    }

    /**
     * @param restApi to route requests with
     * @param errorHandler to render error responses with
     * @param channelErrorHandler to report channel failures to
     * @param responseChunks policy and accounting shared by every channel of this server, so build it once
     * @param observers asked for an observer per request, or null to observe nothing. A
     *                  {@link RestApiObserverFactory} additionally gets the stages after routing
     */
    public RestApiHandler(final RestRouter restApi,
                          final HttpErrorHandler errorHandler,
                          final ChannelErrorHandler channelErrorHandler,
                          final ResponseChunks responseChunks,
                          final HttpObserverFactory observers) {
        this.api = restApi;
        this.errorHandler = errorHandler;
        this.channelErrorHandler = channelErrorHandler;
        this.responseChunks = responseChunks;
        this.observers = observers;
        // asked once, when the server is assembled, rather than on every request
        this.restObservers = observers instanceof RestApiObserverFactory
                ? (RestApiObserverFactory) observers
                : null;
    }

    /**
     * Says that the peer stopped taking the response being written, so that the cursor behind it reports
     * having been given up on rather than looking like an ordinary disconnect. The event comes from
     * {@link ResponseDeadlineHandler}, which knows that nothing is reaching the peer and not what was being
     * sent; this is the other half of that.
     *
     * @param ctx of this handler.
     * @param evt which happened on this channel.
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx,
                                   final Object evt) throws Exception {
        if (evt == ResponseDeadlineHandler.RESPONSE_STALLED && stalling != null) {
            stalling.markStalled();
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * @param body being written a chunk at a time, or null once it is no longer being written.
     */
    void stalling(final ChunkedResponseBody body) {
        stalling = body;
    }

    /**
     * The one way anything here reaches the {@link ChannelErrorHandler}: a failure of the channel rather
     * than of a request, and a handler answering one request twice, which is a mistake of this server and
     * not an answer to give the peer.
     *
     * @param channel it happened on
     * @param cause of it
     */
    void report(final Channel channel,
                final Throwable cause) {
        if (channelErrorHandler != null) {
            channelErrorHandler.onError(channel, cause);
        }
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx,
                             final FullHttpRequest request) {

        final HttpObserver observer = observers != null
                ? observers.newObserver()
                : null;

        // safe by construction: restObservers is that same factory, and RestApiObserverFactory narrows the
        // return type of newObserver, so it cannot have produced anything else
        final RestApiObserver restObserver = restObservers != null
                ? (RestApiObserver) observer
                : null;

        if (observer != null) {
            observer.onRequestReceived(ctx, request);
        }

        final RestResult result = new RestResult(
                ctx,
                request,
                this,
                errorHandler,
                responseChunks,
                observer
        );

        final RestHandling handling;
        try {
            handling = api.resolve(request);
        } catch (final HttpException notRouted) {
            // the request reached no endpoint, so nothing behind the API was touched, and the error is the
            // routing itself rather than anything a handler did
            if (observer != null) {
                observer.onRequestNotRouted(notRouted);
            }
            result.error(notRouted);
            return;
        }

        result.routed(restObserver);

        final RestContext context = new RestContext(
                ctx,
                request,
                handling,
                result.responseHeaders(),
                responseChunks,
                restObserver
        );

        if (restObserver != null) {
            restObserver.onHandlingStarted(context);
        }

        try {
            handling.handle().handle(
                    context,
                    result
            );
        } catch (final Exception error) {
            result.error(error);
        } finally {
            // the matcher flyweight the path parameters come from belongs to the next request on this thread
            context.handled();
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx,
                                final Throwable cause) {
        try {
            report(ctx.channel(), cause);
        } finally {
            ctx.close();
        }
    }
}
