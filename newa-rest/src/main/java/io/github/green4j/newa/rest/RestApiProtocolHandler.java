package io.github.green4j.newa.rest;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

public class RestApiProtocolHandler
        extends SimpleChannelInboundHandler<HttpObject>
        implements io.github.green4j.newa.rest.FullHttpResponse {

    private final HttpHandler api;
    private final ErrorHandler errorHandler;

    private AsciiString contentType;
    private byte[] content;
    private int offset;
    private int length;

    public RestApiProtocolHandler(final HttpHandler restApi,
                                  final ErrorHandler errorHandler) {
        this.api = restApi;
        this.errorHandler = errorHandler;
    }

    @Override
    public void setContent(final AsciiString contentType,
                           final byte[] content,
                           final int offset,
                           final int length) {
        this.contentType = contentType;
        this.content = content;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx,
                             final HttpObject msg) {
        if (!(msg instanceof FullHttpRequest)) {
            return;
        }

        final FullHttpRequest request = (FullHttpRequest) msg;

        HttpResponseStatus status = OK;

        resetContent();
        try {
            api.handle(request, this);
        } catch (final MethodNotAllowedException e) {
            status = e.status();
            errorHandler.handle(e, this);
        } catch (final PathNotFoundException e) {
            status = e.status();
            errorHandler.handle(e, this);
        } catch (final InternalServerErrorException e) {
            status = e.status();
            errorHandler.handle(e, this);
        } catch (final Exception e) {
            final InternalServerErrorException ie = new InternalServerErrorException(e);
            status = ie.status();
            errorHandler.handle(ie, this);
        }

        final boolean keepAlive = HttpUtil.isKeepAlive(request);

        final FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), OK,
                content == null
                        ? Unpooled.EMPTY_BUFFER :
                        Unpooled.wrappedBuffer(content, offset, length));

        response.setStatus(status);

        response.headers()
                .set(CONTENT_TYPE, contentType)
                .setInt(CONTENT_LENGTH, response.content().readableBytes());

        if (keepAlive) {
            if (!request.protocolVersion().isKeepAliveDefault()) {
                response.headers().set(CONNECTION, KEEP_ALIVE);
            }
        } else {
            response.headers().set(CONNECTION, CLOSE);
        }

        final ChannelFuture f = ctx.write(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void resetContent() {
        contentType = null;
        content = null;
        offset = 0;
        length = 0;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        // cause.printStackTrace(); TODO: logging?
        ctx.close();
    }
}
