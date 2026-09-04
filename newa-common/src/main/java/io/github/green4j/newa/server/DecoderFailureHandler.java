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
 * Netty does not answer one by itself, and what it does instead is easy to mistake for something else. A
 * request line past {@code maxInitialLineLength}, or a header block past {@code maxHeaderSize}, makes
 * {@code HttpObjectDecoder} emit a <b>substitute</b> message - {@code GET /bad-request}, carrying the real
 * cause in its {@code decoderResult()} - and the aggregator hands that on like any other request. Without
 * this handler it reaches whatever answers, which finds no such path and says {@code 404}: the one status
 * that tells the caller nothing true about what happened.
 * <p>
 * The connection is finished either way, which is the second half of the reason this exists. The decoder
 * goes to its {@code BAD_MESSAGE} state and discards everything that arrives from then on, so a connection
 * left open after a refusal is one nothing will ever be read from again - it would sit there holding a file
 * descriptor until the idle timeout took it. So the answer carries {@code Connection: close} and the socket
 * goes with it, which is what {@code HttpObjectAggregator} does with the {@code 413} it answers on its own.
 * <p>
 * What is answered, from the cause the decoder recorded:
 * <ul>
 *     <li>{@code 414} for a request line past the limit;</li>
 *     <li>{@code 431} for a header block past it;</li>
 *     <li>{@code 400} for everything else a decoder can refuse - a malformed request line, a header that is
 *     not one, a chunk size that is not a number.</li>
 * </ul>
 * The response has no body. There is nothing to say that the status does not, and a peer whose request was
 * refused mid-header is rarely in a position to read one.
 * <p>
 * It goes behind the aggregator - a refusal is a message like any other, and in front of a decoder there is
 * nothing to see - and in front of everything which answers, so that no handler ever has to ask whether the
 * request it was given was a real one.
 */
public class DecoderFailureHandler extends ChannelInboundHandlerAdapter {
    /**
     * @param ctx of this handler.
     * @param msg the decoder produced, which is a refusal when it carries a failed {@code decoderResult()}.
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) throws Exception {
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
