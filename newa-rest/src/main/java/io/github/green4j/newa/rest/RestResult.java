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

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;

class RestResult implements RestHandle.Result, RestHandle.Result.Content {
    private final HttpHeaders userHeaders = new DefaultHttpHeaders();

    private final ChannelHandlerContext ctx;
    private final HttpVersion httpVersion;
    private final boolean keepAlive;

    private final HttpErrorHandler errorHandler;
    private final ResponseChunks responseChunks;
    private final HttpApiObserver observer;
    private final HttpRequest request;
    private final long startedAt;

    private FullHttpResponse response;
    private boolean routed;

    RestResult(final ChannelHandlerContext ctx,
               final HttpRequest request,
               final HttpErrorHandler errorHandler,
               final ResponseChunks responseChunks,
               final HttpApiObserver observer) {
        this.ctx = ctx;
        this.request = request;

        httpVersion = request.protocolVersion();
        keepAlive = HttpUtil.isKeepAlive(request);

        this.errorHandler = errorHandler;
        this.responseChunks = responseChunks;
        this.observer = observer;

        // the clock is not read at all when there is nobody to report a duration to
        startedAt = observer != null ? System.nanoTime() : 0;
    }

    private boolean observing() {
        return observer != null;
    }

    /**
     * The request reached an endpoint, so a failure from here on is the handler's rather than the routing's -
     * and the routing kind has already been reported as such.
     *
     */
    void routed() {
        routed = true;
    }

    /**
     * The headers a handler adds to this response, handed to {@link RestContext} so that any handler can
     * reach them - the pre-built ones never see this object at all.
     *
     * @return them, to be written into the response before the framework writes its own
     */
    HttpHeaders responseHeaders() {
        return userHeaders;
    }

    @Override
    public void respond(final HttpResponseStatus statusCode) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                statusCode);

        setUserHandlers();

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void respond(final HttpResponseStatus statusCode,
                        final FullHttpResponseContent content) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                statusCode,
                content.toByteBuf(ctx.alloc()));

        setUserHandlers();

        setContentHeaders(
                content.contentEncoding(),
                content.contentType(),
                response.content().readableBytes()
        );

        doDone(false);
    }

    @Override
    public void respond(final HttpResponseStatus statusCode,
                        final AsciiString contentType,
                        final ByteArray content) {
        respond(statusCode, new DefaultFullHttpResponseContent(contentType, content));
    }

    @Override
    public RestHandle.Result.Content respond(final HttpResponseStatus statusCode,
                                             final AsciiString contentEncoding,
                                             final AsciiString contentType,
                                             final int contentLength) {
        // the whole content is declared up front, so allocate it in one go: growing a buffer from
        // scratch would copy the content over and over as it doubles
        response = new DefaultFullHttpResponse(
                httpVersion,
                statusCode,
                contentLength > 0
                        ? ctx.alloc().buffer(contentLength)
                        : ctx.alloc().buffer());

        setUserHandlers();

        setContentHeaders(
                contentEncoding,
                contentType,
                contentLength
        );

        return this;
    }

    @Override
    public void ok() {
        respond(HttpResponseStatus.OK);
    }

    @Override
    public void ok(final byte[] array,
                   final int offset,
                   final int length) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                ctx.alloc().buffer(length).writeBytes(array, offset, length));

        setUserHandlers();

        setContentLengthHeader();

        doDone(false);
    }

    @Override
    public void ok(final ByteBuffer buffer) {
        response = new DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                // duplicated: writeBytes(ByteBuffer) would advance the caller's position
                ctx.alloc().buffer(buffer.remaining()).writeBytes(buffer.duplicate()));

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
        respond(HttpResponseStatus.OK, content);
    }

    @Override
    public void ok(final AsciiString contentType,
                   final ByteArray content) {
        respond(HttpResponseStatus.OK, contentType, content);
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
    public void respond(final HttpResponseStatus statusCode,
                        final AsciiString contentType,
                        final ChunkedInput<ByteBuf> body) {
        final HttpResponse head = new DefaultHttpResponse(httpVersion, statusCode);
        final HttpHeaders headers = head.headers();

        applyUserHeaders(headers);

        if (contentType != null) {
            headers.set(CONTENT_TYPE, contentType);
        }

        // HTTP/1.0 has no chunked encoding: there the only framing left for a body of unknown length is the
        // connection close
        final boolean chunked = httpVersion.isKeepAliveDefault();
        if (chunked) {
            headers.set(TRANSFER_ENCODING, CHUNKED);
        }

        final boolean stillKeepAlive = keepAlive && chunked;
        if (!stillKeepAlive) {
            headers.set(CONNECTION, CLOSE);
        }

        ensureChunkedWrites();

        ctx.write(head);

        // ownership of the body passes to the pipeline here: ChunkedWriteHandler closes it whether it runs to
        // the end, fails, or the channel goes away under it
        final ChannelFuture written = ctx.writeAndFlush(new HttpChunkedInput(body));

        written.addListener((ChannelFutureListener) completed -> {
            if (!completed.isSuccess()) {
                // the write never reached ChunkedWriteHandler, so nothing else is going to close the body.
                // Closing twice is harmless, closing zero times leaks whatever the cursor holds
                closeQuietly(body);
            }
            if (!stillKeepAlive || !completed.isSuccess()) {
                // a body which stopped short of its terminating chunk must not leave the peer waiting for
                // the rest of a response which is not coming
                completed.channel().close();
            }
            if (observing()) {
                // the terminal event of the chunked form. The body is closed by now either way - by
                // ChunkedWriteHandler before it completed this future, or just above - so the cursor has
                // already reported itself
                reportCompleted(statusCode, body.progress());
            }
        });

        ChunkedResponseWatchdog.watch(ctx, body, written, responseChunks.stallTimeoutMillis());
    }

    /**
     * Reports the one terminal event a request gets, whatever form its response took.
     *
     * @param status responded with
     * @param bytes of content written
     */
    private void reportCompleted(final HttpResponseStatus status,
                                 final long bytes) {
        observer.onRequestCompleted(
                status,
                bytes,
                System.nanoTime() - startedAt
        );
    }

    private static void closeQuietly(final ChunkedInput<ByteBuf> body) {
        try {
            body.close();
        } catch (final Exception ignored) {
            // there is nothing left to report it to: the response has already failed
        }
    }

    @Override
    public void ok(final AsciiString contentType,
                   final ChunkedInput<ByteBuf> body) {
        respond(HttpResponseStatus.OK, contentType, body);
    }

    /**
     * Puts a {@link ChunkedWriteHandler} in front of this one, once per channel and only when a chunked
     * response is actually served: it queues everything written through it, which ordinary responses have no
     * use for.
     */
    private void ensureChunkedWrites() {
        final ChannelPipeline pipeline = ctx.pipeline();
        if (pipeline.get(ChunkedWriteHandler.class) == null) {
            pipeline.addBefore(ctx.name(), null, new ChunkedWriteHandler());
        }
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
        return respond(
                HttpResponseStatus.OK,
                contentEncoding,
                contentType,
                contentLength
        );
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
        // a response already built by the failed handler is never written now: its buffer comes from the
        // channel's allocator, so it has to be handed back rather than left to the garbage collector
        releaseResponse();

        // an error which says how it is answered is answered that way, whoever declared it - a user's own
        // exception carries its own status here. Anything else is a failure rather than an answer
        final HttpException answered = error instanceof HttpException
                ? (HttpException) error
                : new InternalServerErrorException(error);

        final HttpResponseStatus status = answered.status();
        final FullHttpResponseContent content = errorHandler.handle(answered);

        if (routed && observer != null) {
            // reported as it was thrown, not as it was wrapped: the response says only the status, so this
            // is the only place the failure is told in full. A request which routed to nothing has already
            // been reported by onRequestNotRouted
            observer.onResponseFailed(status, error);
        }

        response = new DefaultFullHttpResponse(
                httpVersion,
                status,
                content.toByteBuf(ctx.alloc()));

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
            final IllegalStateException error = new IllegalStateException("Expected content length: "
                    + contentLengthHeader + ", in fact: " + contentLength);
            // nothing will be written, so the content buffer goes back to the channel's allocator
            releaseResponse();
            throw error;
        }
    }

    private void releaseResponse() {
        if (response == null) {
            return;
        }
        final FullHttpResponse released = response;
        response = null;
        released.release();
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

        // ownership of the response, and of its buffer, passes to the pipeline here: whatever happens next
        // must not release it a second time
        final FullHttpResponse written = response;
        response = null;

        // read off the response before it is written: the encoder consumes the content buffer, so by the
        // time the write completes there is nothing readable left to measure
        final HttpResponseStatus status = observing() ? written.status() : null;
        final int bytes = observing() ? written.content().readableBytes() : 0;

        final ChannelFuture f = ctx.writeAndFlush(written);
        if (!stillKeepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }

        if (observing()) {
            f.addListener(completed -> reportCompleted(status, bytes));
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
        applyUserHeaders(response.headers());
    }

    /**
     * Copies what the handler asked for into the response, less the three headers which say how the response
     * is framed. Those are the framework\'s: a response claiming to be 9999 bytes long when it is 7, or
     * claiming the connection closes when it does not, is not something a handler gets to say - and on a
     * chunked response a stray {@code Content-Length} is worse than wrong.
     *
     * @param headers of the response being sent
     */
    private void applyUserHeaders(final HttpHeaders headers) {
        if (userHeaders.isEmpty()) {
            return; // the usual case: no handler asked for anything
        }

        userHeaders.remove(CONTENT_LENGTH);
        userHeaders.remove(TRANSFER_ENCODING);
        userHeaders.remove(CONNECTION);

        headers.set(userHeaders);
    }

    private void setContentLengthHeader() {
        final HttpHeaders headers = response.headers();

        final int contentLength = response.content().readableBytes();

        if (contentLength > -1) {
            headers.setInt(CONTENT_LENGTH, contentLength);
        }
    }
}
