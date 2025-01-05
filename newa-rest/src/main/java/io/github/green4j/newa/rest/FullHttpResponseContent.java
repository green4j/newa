package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

public interface FullHttpResponseContent {
    void set(AsciiString contentType,
             ByteBuffer byteBuffer);

    void set(AsciiString contentEncoding,
             AsciiString contentType,
             ByteBuffer byteBuffer);

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
