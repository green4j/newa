package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;

class RestHandlingResult implements RestHandle.Result, RestHandle.Result.Content {
    private final HttpHeaders userHeaders = new DefaultHttpHeaders();

    private final ChannelHandlerContext ctx;
    private final HttpVersion httpVersion;
    private final boolean keepAlive;

    private final ErrorHandler errorHandler;

    private FullHttpResponse response;

    public RestHandlingResult(final ChannelHandlerContext ctx,
                              final HttpMessage request,
                              final ErrorHandler errorHandler) {
        this.ctx = ctx;

        httpVersion = request.protocolVersion();
        keepAlive = HttpUtil.isKeepAlive(request);

        this.errorHandler = errorHandler;
    }

    @Override
    public RestHandle.Result addHeader(final AsciiString header,
                          final AsciiString value) {
        userHeaders.set(header, value);
        return this;
    }

    @Override
    public void ok() {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK);

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void ok(final byte[] array,
                   final int offset,
                   final int length) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer(array, offset, length));

        setUserHandlers();

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void ok(final ByteBuffer buffer) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer(buffer));

        setUserHandlers();

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void ok(final ByteBuf buffer) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                buffer);

        setUserHandlers();

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void ok(final FullHttpResponseContent content) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                content.toByteBuf());

        setUserHandlers();

        setContentHeaders(
                content.contentEncoding(),
                content.contentType(),
                response.content().readableBytes()
        );

        doDone(false);
    }

    @Override
    public void ok(final AsciiString contentType,
                   final ByteArray content) {
        ok(new DefaultFullHttpResponseContent(contentType, content));
    }

    @Override
    public void ok(final AsciiString contentType,
                   final byte[] array,
                   final int offset,
                   final int length) {
        ok(new DefaultFullHttpResponseContent(contentType, array, offset, length));
    }

    @Override
    public void ok(final AsciiString contentType,
                   final ByteBuffer buffer) {
        ok(new DefaultFullHttpResponseContent(contentType, buffer));
    }

    @Override
    public void ok(final AsciiString contentType,
                   final ByteBuf buffer) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                buffer);

        setUserHandlers();

        setContentHeaders(
                null,
                contentType,
                response.content().readableBytes()
        );

        doDone(false);
    }

    @Override
    public void okAndClose() {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK);

        setUserHandlers();

        setContentLengthHeader();

        doDone(true);
    }

    @Override
    public void error(final Exception error) {
        doError(error);

        doDone(false);
    }

    @Override
    public void errorAndClose(final Exception error) {
        doError(error);

        doDone(true);
    }

    @Override
    public RestHandle.Result.Content ok(final AsciiString contentEncoding,
                                        final AsciiString contentType,
                                        final int contentLength) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK);

        setUserHandlers();

        setContentHeaders(
                contentEncoding,
                contentType,
                contentLength
        );

        return this;
    }

    @Override
    public RestHandle.Result.Content ok(final AsciiString contentType,
                                        final int contentLength) {
        return ok(null, contentType, contentLength);
    }

    @Override
    public Content append(final byte[] array,
                          final int offset,
                          final int length) {
        response.content().writeBytes(array, offset, length);
        return this;
    }

    @Override
    public Content append(final ByteBuffer buffer) {
        response.content().writeBytes(buffer);
        return this;
    }

    @Override
    public Content append(final ByteBuf buffer) {
        response.content().writeBytes(buffer);
        return this;
    }

    @Override
    public void done() {
        checkContentLength();
        doDone(false);
    }

    @Override
    public void doneAndClose() {
        checkContentLength();
        doDone(true);
    }

    private void doError(final Exception error) {
        final FullHttpResponseContent content;
        final HttpResponseStatus status;

        if (error instanceof MethodNotAllowedException) {
            final MethodNotAllowedException e =
                    (MethodNotAllowedException) error;
            status = e.status();
            content = errorHandler.handle(e);
        } else if (error instanceof PathNotFoundException) {
            final PathNotFoundException e =
                    (PathNotFoundException) error;
            status = e.status();
            content = errorHandler.handle(e);
        } else if (error instanceof InternalServerErrorException) {
            final InternalServerErrorException e =
                    (InternalServerErrorException) error;
            status = e.status();
            content = errorHandler.handle(e);
        } else {
            final InternalServerErrorException ie = new InternalServerErrorException(error);
            status = ie.status();
            content = errorHandler.handle(ie);
        }

        response = new DefaultFullHttpResponse(
                httpVersion,
                status,
                content.toByteBuf());

        setContentHeaders(
                content.contentEncoding(),
                content.contentType(),
                response.content().readableBytes()
        );
    }

    private void checkContentLength() {
        final int contentLength = response.content().readableBytes();
        if (contentLength < 1) {
            return;
        }

        final HttpHeaders headers = response.headers();
        final int contentLengthHeader = headers.getInt(CONTENT_LENGTH, 0);
        if (contentLength != contentLengthHeader) {
            throw new IllegalStateException("Expected content length: " +
                    contentLengthHeader + ", in fact: " + contentLength);
        }
    }

    private void doDone(final boolean close) {
        final HttpHeaders headers = response.headers();

        final boolean stillKeepAlive = keepAlive & !close;

        if (stillKeepAlive) {
            if (!httpVersion.isKeepAliveDefault()) {
                headers.set(CONNECTION, KEEP_ALIVE);
            }
        } else {
            headers.set(CONNECTION, CLOSE);
        }

        final ChannelFuture f = ctx.writeAndFlush(response);
        if (!stillKeepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void setContentHeaders(final AsciiString contentEncoding,
                                   final AsciiString contentType,
                                   final int contentLength) {
        final HttpHeaders headers = response.headers();

        if (contentEncoding != null) {
            headers.set(CONTENT_ENCODING, contentEncoding);
        }
        if (contentType != null) {
            headers.set(CONTENT_TYPE, contentType);
        }

        if (contentLength > -1) {
            headers.setInt(CONTENT_LENGTH, contentLength);
        }
    }

    private void setUserHandlers() {
        response.headers().set(userHeaders);
    }

    private void setContentLengthHeader() {
        final HttpHeaders headers = response.headers();

        final int contentLength = response.content().readableBytes();

        if (contentLength > -1) {
            headers.setInt(CONTENT_LENGTH, contentLength);
        }
    }
}
