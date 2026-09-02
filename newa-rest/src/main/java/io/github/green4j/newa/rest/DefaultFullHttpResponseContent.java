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

import io.github.green4j.jelly.ByteArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

public class DefaultFullHttpResponseContent implements FullHttpResponseContent {
    private AsciiString contentEncoding;
    private AsciiString contentType;

    private byte[] array;
    private int arrayOffset;
    private int arrayLength;

    private ByteBuffer byteBuffer;

    public DefaultFullHttpResponseContent() {
    }

    public DefaultFullHttpResponseContent(final AsciiString contentType,
                                          final ByteArray byteArray) {
        this.contentType = contentType;
        this.array = byteArray.array();
        this.arrayOffset = byteArray.start();
        this.arrayLength = byteArray.length();
    }

    public DefaultFullHttpResponseContent(final AsciiString contentType,
                                          final byte[] array,
                                          final int arrayOffset,
                                          final int arrayLength) {
        this.contentType = contentType;
        this.array = array;
        this.arrayOffset = arrayOffset;
        this.arrayLength = arrayLength;
    }

    public DefaultFullHttpResponseContent(final AsciiString contentType,
                                          final ByteBuffer byteBuffer) {
        this.contentType = contentType;
        this.byteBuffer = byteBuffer;
    }

    @Override
    public void set(final AsciiString contentType,
                    final byte[] array,
                    final int offset,
                    final int length) {
        set(
                null,
                contentType,
                array,
                offset,
                length
        );
    }

    @Override
    public void set(final AsciiString contentEncoding,
                    final AsciiString contentType,
                    final byte[] array,
                    final int offset,
                    final int length) {
        this.contentEncoding = contentEncoding;
        this.contentType = contentType;
        this.array = array;
        this.arrayOffset = offset;
        this.arrayLength = length;
    }

    @Override
    public void set(final AsciiString contentType,
                    final ByteBuffer byteBuffer) {
        set(
                null,
                contentType,
                byteBuffer
        );
    }

    @Override
    public void set(final AsciiString contentEncoding,
                    final AsciiString contentType,
                    final ByteBuffer byteBuffer) {
        this.contentEncoding = contentEncoding;
        this.contentType = contentType;
        this.byteBuffer = byteBuffer;
    }

    @Override
    public AsciiString contentEncoding() {
        return contentEncoding;
    }

    @Override
    public AsciiString contentType() {
        return contentType;
    }

    @Override
    public byte[] array() {
        return array;
    }

    @Override
    public int arrayOffset() {
        return arrayOffset;
    }

    @Override
    public int arrayLength() {
        return arrayLength;
    }

    public boolean isEmpty() {
        return (array == null || arrayLength == 0)
                && (byteBuffer == null);
    }

    @Override
    public ByteBuf toByteBuf() {
        if (array != null) {
            if (arrayLength == 0) {
                return Unpooled.EMPTY_BUFFER;
            }
            return Unpooled.copiedBuffer(
                    array,
                    arrayOffset,
                    arrayLength);
        }
        if (byteBuffer != null) {
            return Unpooled.copiedBuffer(byteBuffer);
        }
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    public ByteBuf toByteBuf(final ByteBufAllocator allocator) {
        if (array != null) {
            if (arrayLength == 0) {
                return Unpooled.EMPTY_BUFFER;
            }
            return allocator.buffer(arrayLength)
                    .writeBytes(array, arrayOffset, arrayLength);
        }
        if (byteBuffer != null) {
            final ByteBuffer source = byteBuffer.duplicate();
            return allocator.buffer(source.remaining())
                    .writeBytes(source);
        }
        return Unpooled.EMPTY_BUFFER;
    }

    public void reset() {
        contentType = null;
        array = null;
        arrayOffset = 0;
        arrayLength = 0;
    }
}
