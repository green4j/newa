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

import io.netty.util.AsciiString;

/**
 * Builds {@code Content-Disposition} values, which is what turns a response into a download rather than
 * something the client renders:
 * <pre>{@code
 * context.responseHeaders().set(CONTENT_DISPOSITION, ContentDisposition.attachment("rows.json.gz"));
 * }</pre>
 * Worth going through rather than writing the value by hand: a file name is quoted, and a name which ends the
 * quoted string early is a header the caller did not mean to send.
 */
public final class ContentDisposition {
    private ContentDisposition() {
    }

    /**
     * Builds the value once, so a server which serves the same download over and over pays for it once.
     *
     * @param fileName to offer the response under
     * @return the header value
     * @throws IllegalArgumentException if the name is empty, or holds anything other than printable ASCII - a
     *         name outside it needs the {@code filename*} form of RFC 6266, which this does not write
     */
    public static AsciiString attachment(final String fileName) {
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("An attachment needs a file name");
        }

        final StringBuilder value = new StringBuilder(fileName.length() + 24);
        value.append("attachment; filename=\"");

        for (int i = 0; i < fileName.length(); i++) {
            final char c = fileName.charAt(i);
            if (c < ' ' || c > '~') {
                throw new IllegalArgumentException("A file name must be printable ASCII, which \""
                        + fileName + "\" is not at index " + i);
            }
            if (c == '"' || c == '\\') {
                value.append('\\'); // or it would end the quoted string, or escape whatever follows
            }
            value.append(c);
        }

        value.append('"');
        return AsciiString.cached(value.toString());
    }
}
