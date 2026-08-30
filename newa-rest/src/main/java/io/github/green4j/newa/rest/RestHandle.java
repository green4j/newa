package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

public interface RestHandle {
    interface Result {
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
            throws PathNotFoundException, BadRequestException;
}
