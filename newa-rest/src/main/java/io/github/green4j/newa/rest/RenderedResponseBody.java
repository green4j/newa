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

import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * A {@link ChunkedResponseBody} whose cursor writes characters rather than bytes - JSON or plain text - and
 * so has to go through one of green-jelly's byte-array writers before the bytes can be handed to the channel.
 * <p>
 * That writer is the thread's, shared by every such response the thread serves: a chunk is rendered and
 * copied out within one call, so two responses can never be in it at once. Whatever is per-document - the
 * JSON generator's open scopes, the cursor - belongs to the subclass.
 */
abstract class RenderedResponseBody extends ChunkedResponseBody {
    /**
     * A chunk buffer floors at {@link ResponseChunks#DEFAULT_SIZE} rather than at the size ordinary responses
     * are rendered in: that is about the size a chunk is filled to, so anything smaller would only be grown
     * back. A server configured with larger chunks simply grows it, and the window policy keeps it there for
     * as long as that load lasts.
     *
     * @param writer the buffer renders into
     * @param resize replaces the array behind it, the one operation the writer interface does not expose
     * @return the buffer, sized to the load like every other one here
     */
    static RetainedBuffer<ClearableByteArrayBufferingWriter> newBuffer(
            final ClearableByteArrayBufferingWriter writer,
            final IntConsumer resize) {
        return new RetainedBuffer<>(
                writer,
                w -> w.array() == null ? 0 : w.array().length,
                ClearableByteArrayBufferingWriter::length,
                resize,
                ResponseChunks.DEFAULT_SIZE,
                TimeUnit.MILLISECONDS.toNanos(ResponseBuffers.observationWindowMillis()),
                System::nanoTime
        );
    }

    private final RetainedBuffer<ClearableByteArrayBufferingWriter> buffer;

    RenderedResponseBody(final RestContext context,
                         final RetainedBuffer<ClearableByteArrayBufferingWriter> buffer) {
        super(context);
        this.buffer = buffer;
    }

    /**
     * Points whatever the cursor writes through at this chunk's writer. The writer is the same instance every
     * time, but the binding is redone per chunk because the thread may have served another response in
     * between.
     *
     * @param writer to render this chunk into
     */
    abstract void bind(ClearableByteArrayBufferingWriter writer);

    /**
     * Steps the cursor once.
     *
     * @return false when the document is complete
     */
    abstract boolean writeNext();

    /**
     * Closes whatever the document left open.
     */
    abstract void finish();

    @Override
    final ByteBuf renderChunk(final ByteBufAllocator allocator) {
        final int chunkSize = chunks().size();
        final ClearableByteArrayBufferingWriter writer = buffer.buffer();
        writer.clear();
        bind(writer);

        while (writer.length() < chunkSize) {
            if (!writeNext()) {
                finish();
                markExhausted();
                break;
            }
        }

        final int length = writer.length();
        buffer.onRendered();

        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        return allocator.buffer(length)
                .writeBytes(writer.array(), writer.start(), length);
    }
}
