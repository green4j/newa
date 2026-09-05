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
