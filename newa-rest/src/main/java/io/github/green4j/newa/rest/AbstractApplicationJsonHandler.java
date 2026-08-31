package io.github.green4j.newa.rest;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

import java.util.function.IntConsumer;

public abstract class AbstractApplicationJsonHandler {
    private static final ThreadLocal<RetainedBuffer<ByteArrayJsonGenerator>> ASCII_WRITING_GENERATOR =
            ThreadLocal.withInitial(() -> {
                final AsciiByteArrayWriter writer = new AsciiByteArrayWriter(ResponseBuffers.baseSize());
                return retainedGenerator(writer, size -> writer.set(new byte[size]));
            });
    private static final ThreadLocal<RetainedBuffer<ByteArrayJsonGenerator>> UTF8_WRITING_GENERATOR =
            ThreadLocal.withInitial(() -> {
                final Utf8ByteArrayWriter writer = new Utf8ByteArrayWriter(ResponseBuffers.baseSize());
                return retainedGenerator(writer, size -> writer.set(new byte[size]));
            });

    private static RetainedBuffer<ByteArrayJsonGenerator> retainedGenerator(
            final ClearableByteArrayBufferingWriter writer,
            final IntConsumer resize) {
        return new RetainedBuffer<>(
                new ByteArrayJsonGenerator(writer),
                ByteArrayJsonGenerator::capacity,
                ByteArrayJsonGenerator::length,
                resize
        );
    }

    protected final AsciiString contentType;
    protected final Charset responseCharset;

    protected AbstractApplicationJsonHandler() {
        this(Charset.UTF8);
    }

    protected AbstractApplicationJsonHandler(final Charset responseCharset) {
        contentType = responseCharset.toContentType(HttpHeaderValues.APPLICATION_JSON);
        this.responseCharset = responseCharset;
    }

    protected final ByteArrayJsonGenerator jsonGenerator() {
        return generatorThreadLocal().get().buffer();
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
        generatorThreadLocal().get().onRendered();
    }

    private ThreadLocal<RetainedBuffer<ByteArrayJsonGenerator>> generatorThreadLocal() {
        switch (responseCharset) {
            case US_ASCII:
                return ASCII_WRITING_GENERATOR;
            case UTF8:
                return UTF8_WRITING_GENERATOR;
            default:
                throw new IllegalStateException();
        }
    }
}
