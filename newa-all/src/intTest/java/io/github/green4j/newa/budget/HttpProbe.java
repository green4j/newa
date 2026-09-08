/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One request on one connection, read to the end and closed. What the churn of the soak is made of: the
 * JDK's client pools connections, and connections arriving and leaving is the whole point here.
 */
final class HttpProbe {

    private HttpProbe() {
    }

    /**
     * @param host of the server
     * @param port of the server
     * @param path to ask for
     * @return the status the server answered with, or -1 if it said nothing at all
     * @throws IOException if the socket does
     */
    static int get(final String host,
                   final int port,
                   final String path) throws IOException {
        return exchange(host, port, "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ':' + port + "\r\n"
                + "Connection: close\r\n"
                + "\r\n", null);
    }

    /**
     * @param host of the server
     * @param port of the server
     * @param path to post to
     * @param body to send whole, so that the server answers and lets go of it again
     * @return the status the server answered with, or -1 if it said nothing at all
     * @throws IOException if the socket does
     */
    static int post(final String host,
                    final int port,
                    final String path,
                    final byte[] body) throws IOException {
        return exchange(host, port, "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ':' + port + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n", body);
    }

    private static int exchange(final String host,
                                final int port,
                                final String head,
                                final byte[] body) throws IOException {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(15_000);
            socket.connect(new InetSocketAddress(host, port), 10_000);

            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            if (body != null) {
                socket.getOutputStream().write(body);
            }
            socket.getOutputStream().flush();

            final InputStream in = socket.getInputStream();
            final byte[] buffer = new byte[16 * 1024];
            int read = in.read(buffer);
            if (read < 0) {
                return -1; // refused: the connection was closed before a word was said
            }

            final int status = status(new String(buffer, 0, read, StandardCharsets.US_ASCII));
            while (read >= 0) { // to the end, so the server is not left writing into a closed socket
                read = in.read(buffer);
            }
            return status;
        }
    }

    private static int status(final String head) {
        final int first = head.indexOf(' ');
        final int second = head.indexOf(' ', first + 1);
        if (first < 0 || second < 0) {
            return -1;
        }
        return Integer.parseInt(head.substring(first + 1, second).trim());
    }
}
