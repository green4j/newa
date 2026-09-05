/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;

/**
 * Answers a request the decoder refused with what it was refused for, and closes the connection.
 * <p>
 * Netty answers none of them by itself, and what it does instead is easy to mistake for something else: a
 * request line past {@code maxInitialLineLength} or a header block past {@code maxHeaderSize} makes
 * {@code HttpObjectDecoder} emit a <b>substitute</b> message - {@code GET /bad-request}, carrying the real
 * cause in its {@code decoderResult()} - which the aggregator hands on like any other request. Without this
 * handler it reaches whatever answers, which finds no such path and says {@code 404}: the one status that
 * tells the caller nothing true.
 * <p>
 * The connection goes with the answer. A decoder which refused is in its {@code BAD_MESSAGE} state and
 * discards everything that arrives from then on, so a connection left open is one nothing will ever be read
 * from again - it would hold a file descriptor until the idle timeout took it. The response therefore
 * carries {@code Connection: close}, as the {@code 413} of {@code HttpObjectAggregator} does.
 * <p>
 * What is answered, from the cause the decoder recorded: {@code 414} for a request line past the limit,
 * {@code 431} for a header block past it, {@code 400} for everything else a decoder can refuse - a malformed
 * request line, a header which is not one, a chunk size which is not a number. There is no body: nothing is
 * to be said that the status does not, and a peer refused mid-header is rarely in a position to read one.
 * <p>
 * It goes behind the aggregator - a refusal is a message like any other, and in front of a decoder there is
 * nothing to see - and in front of everything which answers, so no handler downstream has to ask whether the
 * request it was given was a real one.
 */
public class DecoderFailureHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) throws Exception {
        // a refusal is a message which carries a failed decoderResult()
        if (!(msg instanceof HttpMessage)) {
            super.channelRead(ctx, msg);
            return;
        }

        final Throwable cause = ((HttpMessage) msg).decoderResult().cause();
        if (cause == null) {
            super.channelRead(ctx, msg);
            return;
        }

        refuse(ctx, msg, statusOf(cause));
    }

    private static HttpResponseStatus statusOf(final Throwable cause) {
        if (cause instanceof TooLongHttpLineException) {
            return HttpResponseStatus.REQUEST_URI_TOO_LONG;
        }
        if (cause instanceof TooLongHttpHeaderException) {
            return HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
        }
        return HttpResponseStatus.BAD_REQUEST;
    }

    private static void refuse(final ChannelHandlerContext ctx,
                               final Object msg,
                               final HttpResponseStatus status) {
        ReferenceCountUtil.release(msg);

        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, // the refused request has whatever version the substitute was built
                status,               // with, and answering in it would be answering the substitute
                Unpooled.EMPTY_BUFFER
        );
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
