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
