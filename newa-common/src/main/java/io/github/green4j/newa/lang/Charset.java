/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import io.netty.util.AsciiString;

import java.nio.charset.StandardCharsets;

/**
 * The character encodings a response may declare: the name a content type spells it with, and the JDK
 * charset which makes the bytes.
 */
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

    /**
     * @return the name a content type spells this charset with
     */
    public String charset() {
        return charset;
    }

    /**
     * @param contentType to name this charset in
     * @return {@code contentType} with this charset named in it, so a response declares the encoding of
     *         what it carries rather than leaving the peer to guess it
     */
    public AsciiString toContentType(final AsciiString contentType) {
        return AsciiString.cached(contentType + "; charset=" + charset);
    }

    /**
     * @return the JDK charset this one names, for the places which turn text into bytes themselves rather
     *         than through one of the writers
     */
    public java.nio.charset.Charset javaCharset() {
        return javaCharset;
    }
}
