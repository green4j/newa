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

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.lang.Charset;

/**
 * Serves a {@link ChunkedJsonRestHandle} as a chunked response. Register it with the plain {@code get} /
 * {@code post} / ... methods:
 * {@code builder.get("/orders", new ChunkedJsonRestHandler(context -> new OrderCursor(...)))}.
 * <p>
 * See {@link ResponseChunks} for what bounds the memory such a response costs.
 */
public class ChunkedJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    private static final ThreadLocal<RetainedBuffer<ClearableByteArrayBufferingWriter>> ASCII_CHUNK =
            ThreadLocal.withInitial(() -> {
                final AsciiByteArrayWriter writer = new AsciiByteArrayWriter(ResponseChunks.DEFAULT_SIZE);
                return RenderedResponseBody.newBuffer(writer, size -> writer.set(new byte[size]));
            });
    private static final ThreadLocal<RetainedBuffer<ClearableByteArrayBufferingWriter>> UTF8_CHUNK =
            ThreadLocal.withInitial(() -> {
                final Utf8ByteArrayWriter writer = new Utf8ByteArrayWriter(ResponseChunks.DEFAULT_SIZE);
                return RenderedResponseBody.newBuffer(writer, size -> writer.set(new byte[size]));
            });

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

        final ChunkedResponseBody body = new Body(context, chunkBuffer(), cursor);
        ChunkedResponses.cursorOpened(context);

        try {
            result.ok(contentType, body);
        } catch (final Exception e) {
            // the body closes the cursor and gives the slot back, exactly once
            body.close();
            result.error(e);
        }
    }

    final RetainedBuffer<ClearableByteArrayBufferingWriter> chunkBuffer() {
        switch (responseCharset) {
            case US_ASCII:
                return ASCII_CHUNK.get();
            case UTF8:
                return UTF8_CHUNK.get();
            default:
                throw new IllegalStateException();
        }
    }

    private static final class Body extends RenderedResponseBody {
        // compact, as everywhere else newa generates JSON
        private final JsonGenerator generator = new JsonGenerator(false);
        private final ChunkedJsonRestHandle.Cursor cursor;

        private Body(final RestContext context,
                     final RetainedBuffer<ClearableByteArrayBufferingWriter> buffer,
                     final ChunkedJsonRestHandle.Cursor cursor) {
            super(context, buffer);
            this.cursor = cursor;
        }

        @Override
        void bind(final ClearableByteArrayBufferingWriter writer) {
            // the generator survives between chunks, which is what lets an array opened in the first one be
            // closed in the last; only its output has to be pointed at the buffer again
            generator.setOutput(writer);
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
