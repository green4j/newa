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

/**
 * Serves a {@link ChunkedRestHandle} as a chunked response of whatever content type you give it. Register it
 * with the plain {@code get} / {@code post} / ... methods:
 * <pre>{@code
 * builder.get("/export", new ChunkedRestHandler(
 *         HttpHeaderValues.APPLICATION_OCTET_STREAM,
 *         context -> new ExportCursor(...)));
 * }</pre>
 * To make it a download rather than something the client renders, set the header where any handler sets one -
 * on the context, before returning the cursor:
 * <pre>{@code
 * context.responseHeaders().set(CONTENT_DISPOSITION, ContentDisposition.attachment("export.bin"));
 * }</pre>
 * See {@link ResponseChunks} for what bounds the memory such a response costs.
 */
public class ChunkedRestHandler implements RestHandle {
    private final AsciiString contentType;
    private final ChunkedRestHandle handle;

    public ChunkedRestHandler(final AsciiString contentType,
                              final ChunkedRestHandle handle) {
        this.contentType = contentType;
        this.handle = handle;
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        if (!ChunkedResponses.admit(context, result)) {
            return;
        }

        final ChunkedRestHandle.Cursor cursor;
        try {
            cursor = handle.open(context);
        } catch (final Exception e) {
            // no cursor exists, so nothing else is going to give the slot back
            ChunkedResponses.giveBackSlot(context);
            result.error(e);
            return;
        }

        final ChunkedResponseBody body = new Body(context, cursor);
        ChunkedResponses.cursorOpened(context);

        try {
            result.ok(contentType, body);
        } catch (final Exception e) {
            // the body closes the cursor and gives the slot back, exactly once
            body.close();
            result.error(e);
        }
    }

    private static final class Body extends ChunkedResponseBody {
        private final ChunkedRestHandle.Cursor cursor;

        private Body(final RestContext context,
                     final ChunkedRestHandle.Cursor cursor) {
            super(context);
            this.cursor = cursor;
        }

        @Override
        ByteBuf renderChunk(final ByteBufAllocator allocator) {
            final int chunkSize = chunks().size();
            // the cursor writes into the chunk itself: nothing renders anywhere else first, so nothing is
            // copied on the way out
            final ByteBuf chunk = allocator.buffer(chunkSize);
            try {
                while (chunk.readableBytes() < chunkSize) {
                    if (!cursor.writeNext(chunk)) {
                        markExhausted();
                        break;
                    }
                }
            } catch (final RuntimeException e) {
                chunk.release();
                throw e;
            }
            return chunk;
        }

        @Override
        void closeCursor() {
            cursor.close();
        }
    }
}
