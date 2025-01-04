package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import java.nio.ByteBuffer;

class DefaultFullHttpResponseContent implements FullHttpResponseContent {
    private AsciiString contentEncoding;
    private AsciiString contentType;

    private byte[] array;
    private int arrayOffset;
    private int arrayLength;

    private ByteBuffer byteBuffer;

    DefaultFullHttpResponseContent() {
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
            return Unpooled.wrappedBuffer(
                    array,
                    arrayOffset,
                    arrayLength);
        }
        if (byteBuffer != null) {
            return Unpooled.wrappedBuffer(byteBuffer);
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
