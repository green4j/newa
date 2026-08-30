package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

public class RestApiHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RestRouter api;
    private final ErrorHandler errorHandler;
    private final ChannelErrorHandler channelErrorHandler;
    private final ResponseChunks responseChunks;
    private final HttpApiObserverFactory observers;
    private final RestApiObserverFactory restObservers;

    public RestApiHandler(final RestRouter restApi,
                          final ErrorHandler errorHandler,
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
                          final ErrorHandler errorHandler,
                          final ChannelErrorHandler channelErrorHandler,
                          final ResponseChunks responseChunks,
                          final HttpApiObserverFactory observers) {
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

    @Override
    public void channelRead0(final ChannelHandlerContext ctx,
                             final FullHttpRequest request) {

        final HttpApiObserver observer = observers != null
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
                errorHandler,
                responseChunks,
                observer,
                restObserver
        );

        final RestHandling handling;
        try {
            handling = api.resolve(request);
        } catch (final RestException notRouted) {
            // the request reached no endpoint, so nothing behind the API was touched, and the error is the
            // routing itself rather than anything a handler did
            if (observer != null) {
                observer.onRequestNotRouted(notRouted);
            }
            result.error(notRouted);
            return;
        }

        result.routed();

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
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(ctx.channel(), cause);
            }
        } finally {
            ctx.close();
        }
    }
}
