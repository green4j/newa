package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;

public class RestApiProtocolHandler
        extends SimpleChannelInboundHandler<HttpObject> {

    private final RestRouter api;
    private final ErrorHandler errorHandler;
    private final ChannelErrorHandler channelErrorHandler;

    public RestApiProtocolHandler(final RestRouter restApi,
                                  final ErrorHandler errorHandler,
                                  final ChannelErrorHandler channelErrorHandler) {
        this.api = restApi;
        this.errorHandler = errorHandler;
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx,
                             final HttpObject msg) {
        if (!(msg instanceof FullHttpRequest)) {
            return;
        }

        final FullHttpRequest request = (FullHttpRequest) msg;

        final RestHandlingResult result = new RestHandlingResult(
                ctx,
                request,
                errorHandler
        );

        try {
            final RestHandling handling = api.resolve(request);

            final RestHandle handle = handling.handle();
            final PathParameters pathParameters = handling.pathParameters();

            handle.handle(
                    ctx,
                    request,
                    pathParameters,
                    result
            );
        } catch (final Exception error) {
            result.error(error);
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
