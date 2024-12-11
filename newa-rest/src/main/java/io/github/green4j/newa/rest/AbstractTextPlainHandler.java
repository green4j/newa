package io.github.green4j.newa.rest;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

public abstract class AbstractTextPlainHandler {
    private static final int INITIAL_SIZE = 1024;

    private static final ThreadLocal<ByteArrayLineBuilder> ASCII_LINE_BUILDER =
            ThreadLocal.withInitial(() -> new ByteArrayLineBuilder(new AsciiByteArrayWriter(INITIAL_SIZE)));
    private static final ThreadLocal<ByteArrayLineBuilder> UTF8_LINE_BUILDER =
            ThreadLocal.withInitial(() -> new ByteArrayLineBuilder(new Utf8ByteArrayWriter(INITIAL_SIZE)));

    protected final AsciiString contentType;
    protected final Charset responseCharset;

    protected AbstractTextPlainHandler() {
        this(Charset.UTF8);
    }

    protected AbstractTextPlainHandler(final Charset responseCharset) {
        contentType = AsciiString.of(HttpHeaderValues.TEXT_PLAIN
                + "; charset=" + responseCharset.charset());
        this.responseCharset = responseCharset;
    }

    protected final ByteArrayLineBuilder lineBuilder() {
        final ByteArrayLineBuilder result;
        switch (responseCharset) {
            case US_ASCII:
                result = ASCII_LINE_BUILDER.get();
                break;
            case UTF8:
                result = UTF8_LINE_BUILDER.get();
                break;
            default:
                throw new IllegalStateException();
        }
        result.clear();
        return result;
    }
}
