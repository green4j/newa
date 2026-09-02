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
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

import java.util.function.IntConsumer;

public abstract class AbstractTextPlainHandler {
    private static final ThreadLocal<RetainedBuffer<ByteArrayLineBuilder>> ASCII_LINE_BUILDER =
            ThreadLocal.withInitial(() -> {
                final AsciiByteArrayWriter writer = new AsciiByteArrayWriter(ResponseBuffers.baseSize());
                return retainedLineBuilder(writer, size -> writer.set(new byte[size]));
            });
    private static final ThreadLocal<RetainedBuffer<ByteArrayLineBuilder>> UTF8_LINE_BUILDER =
            ThreadLocal.withInitial(() -> {
                final Utf8ByteArrayWriter writer = new Utf8ByteArrayWriter(ResponseBuffers.baseSize());
                return retainedLineBuilder(writer, size -> writer.set(new byte[size]));
            });

    private static RetainedBuffer<ByteArrayLineBuilder> retainedLineBuilder(
            final ClearableByteArrayBufferingWriter writer,
            final IntConsumer resize) {
        return new RetainedBuffer<>(
                new ByteArrayLineBuilder(writer),
                ByteArrayLineBuilder::capacity,
                lineBuilder -> lineBuilder.array().length(),
                resize
        );
    }

    protected final AsciiString contentType;
    protected final Charset responseCharset;

    protected AbstractTextPlainHandler() {
        this(Charset.UTF8);
    }

    protected AbstractTextPlainHandler(final Charset responseCharset) {
        contentType = responseCharset.toContentType(HttpHeaderValues.TEXT_PLAIN);
        this.responseCharset = responseCharset;
    }

    protected final ByteArrayLineBuilder lineBuilder() {
        final ByteArrayLineBuilder result = lineBuilderThreadLocal().get().buffer();
        result.clear();
        return result;
    }

    /**
     * Reports that a response has been rendered, letting a buffer grown oversized by a large response be
     * shrunk back once the load no longer needs that size - see {@link ResponseBuffers}. Without this a
     * single huge response makes every thread which rendered one hold a buffer of that size for the lifetime
     * of the process.
     * <p>
     * Call this only once the rendered content has been copied out of the buffer.
     */
    protected final void responseRendered() {
        lineBuilderThreadLocal().get().onRendered();
    }

    private ThreadLocal<RetainedBuffer<ByteArrayLineBuilder>> lineBuilderThreadLocal() {
        switch (responseCharset) {
            case US_ASCII:
                return ASCII_LINE_BUILDER;
            case UTF8:
                return UTF8_LINE_BUILDER;
            default:
                throw new IllegalStateException();
        }
    }
}
