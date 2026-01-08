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

    public RestApiHandler(final RestRouter restApi,
                          final ErrorHandler errorHandler,
                          final ChannelErrorHandler channelErrorHandler) {
        this.api = restApi;
        this.errorHandler = errorHandler;
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx,
                             final FullHttpRequest request) {

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
