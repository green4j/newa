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
