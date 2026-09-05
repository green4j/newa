/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.rest.FullHttpResponseContent;
import io.github.green4j.newa.rest.HttpObserver;
import io.github.green4j.newa.rest.HttpObserverFactory;
import io.github.green4j.newa.rest.HttpErrorHandler;
import io.github.green4j.newa.rest.PathNotFoundException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;

/**
 * The end of a file server's pipeline: a request no file owns, and which no handler of yours took, is
 * answered {@code 404}.
 * <p>
 * Something has to be here, because {@link FileServerHandler} passes on what it does not own. With nothing
 * behind it such a request reaches the end of the pipeline, where it is discarded in silence while
 * <b>the connection stays open for as long as the peer keeps it</b>.
 * <p>
 * It answers with {@link FileServerHandler}'s own {@code 404}, down to the message, and that is the point of
 * it rather than a convenience: a path which is not served and a file which may not be served have to look
 * the same, or the shape of the answer tells whoever is asking which prefixes this server serves and which
 * files it is hiding. Down to the headers, too - the {@code nosniff} the file handler puts on
 * everything it answers is on this as well.
 * <p>
 * {@link FileServer} puts one at the end of every pipeline it assembles,
 * behind the handlers added with {@code withHandler}: a {@code RestApiHandler} there answers first, and what
 * reaches here is only what nothing else wanted. A pipeline written by hand wants one too, last.
 * <p>
 * The counterpart of {@code io.github.green4j.newa.websocket.HandshakeOnlyHandler}, which has no response to
 * give and closes instead.
 */
public class FilesOnlyHandler extends SimpleChannelInboundHandler<HttpRequest> {
    private final HttpErrorHandler errorHandler;
    private final HttpObserverFactory observerFactory;

    /**
     * @param errorHandler rendering the {@code 404}
     */
    public FilesOnlyHandler(final HttpErrorHandler errorHandler) {
        this(errorHandler, null);
    }

    /**
     * @param errorHandler rendering the {@code 404}
     * @param observerFactory asked for an observer per request, or null to observe nothing. Give it the one
     *                        the file handler was given and a request is observed once wherever it was
     *                        answered
     */
    public FilesOnlyHandler(final HttpErrorHandler errorHandler,
                            final HttpObserverFactory observerFactory) {
        this.errorHandler = errorHandler;
        this.observerFactory = observerFactory;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,
                                final HttpRequest request) {
        final HttpObserver observer = observerFactory != null ? observerFactory.newObserver() : null;
        final long startedAt = observer != null ? System.nanoTime() : 0;

        final PathNotFoundException error = FileServerHandler.notFound();

        if (observer != null) {
            observer.onRequestReceived(ctx, request);
            observer.onRequestNotRouted(error); // nothing here served it, which is the whole of the report
        }

        final FullHttpResponseContent content = errorHandler.handle(error);
        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.protocolVersion(), error.status(), content.toByteBuf(ctx.alloc()));

        final boolean keepAlive;
        try {
            final HttpHeaders headers = response.headers();
            headers.set(FileServerHandler.CONTENT_TYPE_OPTIONS, FileServerHandler.NOSNIFF);
            if (content.contentType() != null) {
                headers.set(CONTENT_TYPE, content.contentType());
            }
            headers.setInt(CONTENT_LENGTH, response.content().readableBytes());

            keepAlive = HttpUtil.isKeepAlive(request) && ctx.channel().isActive();
            if (keepAlive) {
                if (!request.protocolVersion().isKeepAliveDefault()) {
                    headers.set(CONNECTION, KEEP_ALIVE);
                }
            } else {
                headers.set(CONNECTION, CLOSE);
            }
        } catch (final RuntimeException failed) {
            // the buffer came from the channel's allocator and is nobody else's yet
            response.release();
            throw failed;
        }

        ctx.writeAndFlush(response).addListener((ChannelFutureListener) completed -> {
            if (!keepAlive || !completed.isSuccess()) {
                completed.channel().close();
            }
            if (observer != null) {
                observer.onRequestCompleted(error.status(), 0, System.nanoTime() - startedAt);
            }
        });
    }
}
