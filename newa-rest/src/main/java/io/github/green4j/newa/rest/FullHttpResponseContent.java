package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

public interface FullHttpResponseContent {
    default void set(String contentType,
                     ByteBuffer byteBuffer) {
        set(
                AsciiString.of(contentType),
                byteBuffer
        );
    }

    default void set(String contentEncoding,
                     String contentType,
                     ByteBuffer byteBuffer) {
        set(
                AsciiString.of(contentEncoding),
                AsciiString.of(contentType),
                byteBuffer
        );
    }

    void set(AsciiString contentType,
             ByteBuffer byteBuffer);

    void set(AsciiString contentEncoding,
             AsciiString contentType,
             ByteBuffer byteBuffer);

    default void set(String contentType,
                     byte[] array,
                     int offset,
                     int length) {
        set(
                AsciiString.of(contentType),
                array,
                offset,
                length
        );
    }

    default void set(String contentEncoding,
                     String contentType,
                     byte[] array,
                     int offset,
                     int length) {
        set(
                AsciiString.of(contentEncoding),
                AsciiString.of(contentType),
                array,
                offset,
                length
        );
    }

    void set(AsciiString contentType,
             byte[] array,
             int offset,
             int length);

    void set(AsciiString contentEncoding,
             AsciiString contentType,
             byte[] array,
             int offset,
             int length);

    AsciiString contentEncoding();

    AsciiString contentType();

    byte[] array();

    int arrayOffset();

    int arrayLength();

    ByteBuf toByteBuf();
}
