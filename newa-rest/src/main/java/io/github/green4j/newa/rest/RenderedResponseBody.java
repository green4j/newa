/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteBufWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link ChunkedResponseBody} whose cursor writes characters rather than bytes - JSON or plain text - and
 * so goes through a {@link ByteBufWriter}: a chunk is written into buffers of the allocator the way a whole
 * response is, through the thread's array of at most 256 KiB. A step of the cursor which writes more than that
 * goes into more buffers, never into a larger array.
 * <p>
 * The writer is taken for one chunk and given back, so the thread's writer serves every response the thread
 * serves. Whatever is per-document - the JSON generator's open scopes, the cursor - belongs to the subclass.
 */
abstract class RenderedResponseBody extends ChunkedResponseBody {
    private final Charset charset;

    RenderedResponseBody(final RestContext context,
                         final Charset charset) {
        super(context);
        this.charset = charset;
    }

    /**
     * Points whatever the cursor writes through at this chunk's writer: the thread may have served another
     * response since the last chunk, or have given a writer of its own to this one.
     *
     * @param writer to write this chunk into
     */
    abstract void bind(ByteBufWriter writer);

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
        final ByteBufWriter writer = ByteBufWriter.start(charset, allocator);
        try {
            bind(writer);
            boolean last = false;
            while (writer.length() < chunkSize) {
                if (!writeNext()) {
                    finish();
                    last = true;
                    break;
                }
            }
            final ByteBuf chunk = writer.finish(); // empty if the document ended exactly on a chunk boundary
            if (last) {
                // only once the last chunk exists: one which could not be allocated is not a completed response
                markExhausted();
            }
            return chunk;
        } finally {
            writer.abort(); // a cursor or an allocation which threw: the buffers taken so far go back
        }
    }
}
