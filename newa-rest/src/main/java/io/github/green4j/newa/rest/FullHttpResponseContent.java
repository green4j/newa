package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
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

    /**
     * Copies the content into a buffer taken from {@code allocator}, which is expected to be the allocator of
     * the channel the response is written to. A heap buffer would be copied into a direct one by the transport
     * right before the socket write, doubling the peak footprint of the response; allocating from the channel's
     * allocator gives the transport a buffer it can write as is.
     *
     * @param allocator allocator of the channel the response is written to
     * @return buffer holding the content, owned by the caller
     */
    default ByteBuf toByteBuf(final ByteBufAllocator allocator) {
        return toByteBuf();
    }
}
