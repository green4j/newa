package io.github.green4j.newa.lang;

import io.netty.util.AsciiString;

import java.nio.charset.StandardCharsets;

public enum Charset {
    US_ASCII("us-ascii", StandardCharsets.US_ASCII),
    UTF8("utf-8", StandardCharsets.UTF_8);

    private final String charset;
    private final java.nio.charset.Charset javaCharset;

    Charset(final String charset,
            final java.nio.charset.Charset javaCharset) {
        this.charset = charset;
        this.javaCharset = javaCharset;
    }

    public String charset() {
        return charset;
    }

    /**
     * {@code contentType} with this charset named in it, so that a response declares the encoding of what
     * it carries rather than leaving the peer to guess.
     *
     * @param contentType to name this charset in
     * @return the content type carrying the charset parameter
     */
    public AsciiString toContentType(final AsciiString contentType) {
        return AsciiString.cached(contentType + "; charset=" + charset);
    }

    /**
     * The JDK charset this one names, for the places which turn text into bytes themselves rather than
     * through one of the writers.
     *
     * @return the JDK charset this one names
     */
    public java.nio.charset.Charset javaCharset() {
        return javaCharset;
    }
}
