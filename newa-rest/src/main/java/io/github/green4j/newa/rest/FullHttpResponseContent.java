/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

/**
 * The body of a response which is written in one go, and the type of it: a {@code byte[]}, a
 * {@link ByteBuffer} or a {@link ByteBuf}, with the content type and encoding to declare.
 * <p>
 * A handler fills one and hands it to {@code Result.ok(...)}; the framework then copies what it holds into a
 * buffer of the channel's allocator, so what was set may be reused as soon as the call returns.
 */
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
