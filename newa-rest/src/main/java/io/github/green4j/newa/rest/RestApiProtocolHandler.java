package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;

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

        try {
            final RestHandling handling = api.resolve(request);

            final FullHttpResponseContent responseContent =
                    new DefaultFullHttpResponseContent();

            final RestHandle handle = handling.handle();
            final PathParameters pathParameters = handling.pathParameters();

            handle.handle(
                    ctx,
                    request,
                    pathParameters,
                    responseContent,
                    new RestHandle.Result() {
                        @Override
                        public void ok() {
                            finishWithOk(
                                    ctx,
                                    request,
                                    responseContent,
                                    false
                            );
                        }

                        @Override
                        public void okAndClose() {
                            finishWithOk(
                                    ctx,
                                    request,
                                    responseContent,
                                    true
                            );
                        }

                        @Override
                        public void error(final Exception error) {
                            finishWithError(
                                    ctx,
                                    request,
                                    error,
                                    false
                            );
                        }

                        @Override
                        public void errorAndClose(final Exception error) {
                            finishWithError(
                                    ctx,
                                    request,
                                    error,
                                    true
                            );
                        }
                    });
        } catch (final Exception error) {
            finishWithError(
                    ctx,
                    request,
                    error,
                    false
            );
        }
    }

    private void finishWithOk(final ChannelHandlerContext ctx,
                              final HttpMessage request,
                              final FullHttpResponseContent responseContent,
                              final boolean close) {
        finish(
                ctx,
                request,
                HttpResponseStatus.OK,
                responseContent,
                close
        );
    }

    private void finishWithError(final ChannelHandlerContext ctx,
                                 final HttpMessage request,
                                 final Exception error,
                                 final boolean close) {
        final FullHttpResponseContent responseContent =
                new DefaultFullHttpResponseContent();

        final HttpResponseStatus status;

        if (error instanceof MethodNotAllowedException) {
            final MethodNotAllowedException e =
                    (MethodNotAllowedException) error;
            status = e.status();
            errorHandler.handle(e, responseContent);
        } else if (error instanceof PathNotFoundException) {
            final PathNotFoundException e =
                    (PathNotFoundException) error;
            status = e.status();
            errorHandler.handle(e, responseContent);
        } else if (error instanceof InternalServerErrorException) {
            final InternalServerErrorException e =
                    (InternalServerErrorException) error;
            status = e.status();
            errorHandler.handle(e, responseContent);
        } else {
            final InternalServerErrorException ie = new InternalServerErrorException(error);
            status = ie.status();
            errorHandler.handle(ie, responseContent);
        }

        finish(
                ctx,
                request,
                status,
                responseContent,
                close
        );
    }

    private static void finish(final ChannelHandlerContext ctx,
                               final HttpMessage request,
                               final HttpResponseStatus status,
                               final FullHttpResponseContent responseContent,
                               final boolean close) {

        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.protocolVersion(),
                status,
                responseContent.toByteBuf());

        final HttpHeaders headers = response.headers();

        final AsciiString contentEncoding = responseContent.contentEncoding();
        if (contentEncoding != null) {
            headers
                    .set(CONTENT_ENCODING, contentEncoding);
        }

        headers
                .set(CONTENT_TYPE, responseContent.contentType())
                .setInt(CONTENT_LENGTH,
                        response.content().readableBytes());

        final boolean keepAlive = HttpUtil.isKeepAlive(request) && !close;

        if (keepAlive) {
            if (!request.protocolVersion().isKeepAliveDefault()) {
                response.headers().set(CONNECTION, KEEP_ALIVE);
            }
        } else {
            response.headers().set(CONNECTION, CLOSE);
        }

        final ChannelFuture f = ctx.writeAndFlush(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
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
