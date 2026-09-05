/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

/**
 * What answers one request, in the full form: given the {@link RestContext} and a {@link Result}, and free
 * to answer later - the result may be kept beyond {@code handle}, as long as every call which touches it is
 * back on {@link RestContext#executor()}, the event loop of the channel.
 * <p>
 * A handle which renders its answer and returns is written as a {@link JsonRestHandle} or a
 * {@link TxtRestHandle} instead; this is the one for an answer which is not ready when the request is.
 * <p>
 * What is thrown becomes the response: an {@link HttpException} carries its own status and message, and
 * anything else is a {@code 500} whose cause goes to the observer rather than to the client.
 */
public interface RestHandle {
    /**
     * One request is answered once. Whichever method here sends the response ends this result, and every
     * call which follows - a second {@code ok}, an {@code error} after one, an {@code append} after
     * {@code done} - is dropped rather than written: a second response would be read by the peer as the
     * answer to its next request. What was handed to the dropped call is released, and the mistake is
     * reported to the
     * {@link io.github.green4j.newa.lang.ChannelErrorHandler} of the server, which is where a handler
     * calling this twice - most easily from two callbacks of an async response - is to be found.
     */
    interface Result {
        /**
         * A body written in pieces, for a response whose length was declared up front. The pieces fill the
         * response buffer - nothing is sent until {@link #done()}, which is what makes this different from a
         * chunked response.
         */
        interface Content {
            Content append(byte[] array, int offset, int length);

            Content append(ByteBuffer buffer);

            Content append(ByteBuf buffer);

            void done();

            void doneAndClose();
        }

        void respond(HttpResponseStatus statusCode);

        void respond(HttpResponseStatus statusCode,
                     FullHttpResponseContent content);

        void respond(HttpResponseStatus statusCode,
                     AsciiString contentType,
                     ByteArray content);

        RestHandle.Result.Content respond(HttpResponseStatus statusCode,
                                          AsciiString contentEncoding,
                                          AsciiString contentType,
                                          int contentLength);

        void ok();

        void ok(byte[] array, int offset, int length);

        void ok(ByteBuffer buffer);

        void ok(ByteBuf buffer);

        void ok(FullHttpResponseContent content);

        void ok(AsciiString contentType,
                ByteArray content);

        void ok(AsciiString contentType,
                byte[] array, int offset, int length);

        void ok(AsciiString contentType,
                ByteBuffer buffer);

        void ok(AsciiString contentType,
                ByteBuf buffer);

        Content ok(AsciiString contentEncoding,
                   AsciiString contentType,
                   int contentLength);

        Content ok(AsciiString contentType,
                   int contentLength);

        /**
         * Sends a response whose body is pulled from {@code body} a chunk at a time, framed by chunked
         * transfer encoding, and only as fast as the peer takes it. Neither the length nor the content has to
         * be known up front, and neither is ever held in full.
         * <p>
         * Nothing is pulled while the channel is over its write watermark, so configure
         * {@link io.netty.channel.ChannelOption#WRITE_BUFFER_WATER_MARK} - that is where the backpressure
         * comes from.
         *
         * @param statusCode of the response
         * @param contentType of the response, or null
         * @param body to pull the response from; closed however the response ends
         */
        void respond(HttpResponseStatus statusCode,
                     AsciiString contentType,
                     ChunkedInput<ByteBuf> body);

        void ok(AsciiString contentType,
                ChunkedInput<ByteBuf> body);

        void okAndClose();

        void error(Exception error);

        void errorAndClose(Exception error);
    }

    void handle(RestContext context,
                Result result)
            throws HttpException;
}
