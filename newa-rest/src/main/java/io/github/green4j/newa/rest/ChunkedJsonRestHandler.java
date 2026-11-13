/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteBufWriter;

/**
 * Serves a {@link ChunkedJsonRestHandle} as a chunked response. Register it with the plain {@code get} /
 * {@code post} / ... methods:
 * {@code builder.get("/orders", new ChunkedJsonRestHandler(context -> new OrderCursor(...)))}.
 * <p>
 * See {@link ResponseChunks} for what bounds the memory such a response costs.
 */
public class ChunkedJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    private final ChunkedJsonRestHandle handle;

    public ChunkedJsonRestHandler(final ChunkedJsonRestHandle handle) {
        this.handle = handle;
    }

    public ChunkedJsonRestHandler(final Charset responseCharset,
                                  final ChunkedJsonRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        if (!ChunkedResponses.admit(context, result)) {
            return;
        }

        final ChunkedJsonRestHandle.Cursor cursor;
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
        // compact, as everywhere else newa generates JSON
        private final JsonGenerator generator = new JsonGenerator(false);
        private final ChunkedJsonRestHandle.Cursor cursor;

        private Body(final RestContext context,
                     final Charset charset,
                     final ChunkedJsonRestHandle.Cursor cursor) {
            super(context, charset);
            this.cursor = cursor;
        }

        @Override
        void bind(final ByteBufWriter writer) {
            // the generator survives between chunks, which is what lets an array opened in the first one be
            // closed in the last; only its output has to be pointed at the writer again
            generator.setOutput(writer.output());
        }

        @Override
        boolean writeNext() {
            return cursor.writeNext(generator);
        }

        @Override
        void finish() {
            generator.eoj(); // closes every scope the cursor left open, then flushes
        }

        @Override
        void closeCursor() {
            cursor.close();
        }
    }
}
