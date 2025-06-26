package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
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

        RestHandle.Result addHeader(AsciiString header, AsciiString value);

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

        void okAndClose();

        void error(Exception error);

        void errorAndClose(Exception error);
    }

    void handle(ChannelHandlerContext ctx,
                FullHttpRequest request,
                PathParameters pathParameters,
                Result result)
            throws PathNotFoundException, BadRequestException;
}
