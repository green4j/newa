/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.github.green4j.newa.text.ByteBufWriter;

/**
 * Serves a {@link ChunkedTxtRestHandle} as a chunked response. See {@link ResponseChunks} for what bounds the
 * memory such a response costs.
 */
public class ChunkedTxtRestHandler
        extends AbstractTextPlainHandler implements RestHandle {

    private final ChunkedTxtRestHandle handle;

    public ChunkedTxtRestHandler(final ChunkedTxtRestHandle handle) {
        this.handle = handle;
    }

    public ChunkedTxtRestHandler(final Charset responseCharset,
                                 final ChunkedTxtRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        if (!ChunkedResponses.admit(context, result)) {
            return;
        }

        final ChunkedTxtRestHandle.Cursor cursor;
        try {
            cursor = handle.open(context);
        } catch (final Exception e) {
            // no cursor exists, so nothing else is going to give the slot back
            ChunkedResponses.giveBackSlot(context);
            result.error(e);
            return;
        }

        final ChunkedResponseBody body = new Body(context, responseCharset, cursor);
        ChunkedResponses.cursorOpened(context);

        try {
            result.ok(contentType, body);
        } catch (final Exception e) {
            // the body closes the cursor and gives the slot back, exactly once
            body.close();
            result.error(e);
        }
    }

    private static final class Body extends RenderedResponseBody {
        private final ChunkedTxtRestHandle.Cursor cursor;

        private ByteArrayLineBuilder lineBuilder;

        private Body(final RestContext context,
                     final Charset charset,
                     final ChunkedTxtRestHandle.Cursor cursor) {
            super(context, charset);
            this.cursor = cursor;
        }

        @Override
        void bind(final ByteBufWriter writer) {
            lineBuilder = writer.lines(); // plain text keeps nothing between chunks
        }

        @Override
        boolean writeNext() {
            return cursor.writeNext(lineBuilder);
        }

        @Override
        void finish() {
            // plain text has nothing left open
        }

        @Override
        void closeCursor() {
            cursor.close();
        }
    }
}
