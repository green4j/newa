package io.github.green4j.newa.rest;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

public abstract class AbstractApplicationJsonHandler {
    private static final int INITIAL_SIZE = 1024;

    private static final ThreadLocal<ByteArrayJsonGenerator> ASCII_WRITING_GENERATOR =
            ThreadLocal.withInitial(() -> new ByteArrayJsonGenerator(new AsciiByteArrayWriter(INITIAL_SIZE)));
    private static final ThreadLocal<ByteArrayJsonGenerator> UTF8_WRITING_GENERATOR =
            ThreadLocal.withInitial(() -> new ByteArrayJsonGenerator(new Utf8ByteArrayWriter(INITIAL_SIZE)));

    protected final AsciiString contentType;
    protected final Charset responseCharset;

    protected AbstractApplicationJsonHandler() {
        this(Charset.UTF8);
    }

    protected AbstractApplicationJsonHandler(final Charset responseCharset) {
        contentType = AsciiString.of(HttpHeaderValues.APPLICATION_JSON
                + "; charset=" + responseCharset.charset());
        this.responseCharset = responseCharset;
    }

    protected final ByteArrayJsonGenerator jsonGenerator() {
        switch (responseCharset) {
            case US_ASCII:
                return ASCII_WRITING_GENERATOR.get();
            case UTF8:
                return UTF8_WRITING_GENERATOR.get();
            default:
                throw new IllegalStateException();
        }
    }
}
