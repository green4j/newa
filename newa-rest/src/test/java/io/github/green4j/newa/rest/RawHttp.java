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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * An HTTP request written out on a plain socket, for the tests which need a header the JDK's client will
 * not send. {@code Origin} is one of those - it is restricted there, and CORS is not testable without it.
 */
final class RawHttp {
    private final String host;
    private final int port;

    RawHttp(final String host,
            final int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * @param method  to ask with.
     * @param path    to ask for.
     * @param headers extra ones, as whole lines without the terminator.
     * @return the response head, up to and without the empty line which ends it.
     * @throws IOException if the socket does.
     */
    String head(final String method,
                final String path,
                final String... headers) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10_000);

            final StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append(':').append(port).append("\r\n")
                    .append("Connection: close\r\n");
            for (int i = 0; i < headers.length; i++) {
                request.append(headers[i]).append("\r\n");
            }
            request.append("\r\n");

            final OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            return readHead(socket.getInputStream());
        }
    }

    /**
     * @param head    of a response, as {@link #head} returned it.
     * @param name    of a header.
     * @return its value, or null if the response carries none. The name is matched without regard to case,
     *         as a header name is.
     */
    static String valueOf(final String head,
                          final String name) {
        final String prefix = name.toLowerCase(Locale.ROOT) + ":";
        final String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return lines[i].substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * @param head of a response, as {@link #head} returned it.
     * @return its status line.
     */
    static String statusOf(final String head) {
        final int end = head.indexOf("\r\n");
        return end < 0 ? head : head.substring(0, end);
    }

    private static String readHead(final InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();

        int matched = 0; // how much of the empty line which ends a head has been seen
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return head.toString(StandardCharsets.US_ASCII.name());
            }
            head.write(b);
            if (b == '\r') {
                matched = matched == 2 ? 3 : 1;
            } else if (b == '\n') {
                matched = matched == 1 ? 2 : (matched == 3 ? 4 : 0);
            } else {
                matched = 0;
            }
        }

        final String whole = head.toString(StandardCharsets.US_ASCII.name());
        return whole.substring(0, whole.length() - 4);
    }
}
