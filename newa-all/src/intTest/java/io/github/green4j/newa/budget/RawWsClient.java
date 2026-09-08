/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * A WebSocket client on a plain socket, cut down from the one in the websocket module's own tests to the
 * handshake, a masked frame and the frame which comes back. The JDK's client will not do: it frames a large
 * message however it likes, and these tests are about frames of a stated size.
 */
final class RawWsClient implements AutoCloseable {
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final int TEXT = 0x1;

    private static final Random MASKS = new Random(20260908L);

    private final Socket socket;

    RawWsClient(final String host,
                final int port) throws IOException {
        socket = new Socket();
        socket.setSoTimeout(15_000);
        socket.connect(new InetSocketAddress(host, port), 10_000);
    }

    /**
     * @param path to upgrade on
     * @return whether the server accepted the upgrade
     * @throws IOException if the socket does
     */
    boolean handshake(final String path) throws IOException {
        write(("GET " + path + " HTTP/1.1\r\n"
                + "Host: " + socket.getInetAddress().getHostAddress() + ':' + socket.getPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        return readHead().startsWith("HTTP/1.1 101");
    }

    void sendText(final byte[] payload) throws IOException {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | TEXT);

        final int length = payload.length;
        if (length < 126) {
            frame.write(0x80 | length);
        } else if (length < 65536) {
            frame.write(0x80 | 126);
            frame.write((length >>> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((((long) length) >>> (i * 8)) & 0xFF));
            }
        }

        final byte[] mask = new byte[4];
        MASKS.nextBytes(mask);
        frame.write(mask, 0, mask.length);
        for (int i = 0; i < length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }

        write(frame.toByteArray());
    }

    /**
     * @return the payload length of the next frame, or -1 if the connection ended first
     * @throws IOException if the socket does
     */
    int readFrameLength() throws IOException {
        final InputStream in = socket.getInputStream();
        if (in.read() < 0) {
            return -1;
        }

        int length = in.read() & 0x7F; // a server never masks, so the top bit is not one
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            long extended = 0;
            for (int i = 0; i < 8; i++) {
                extended = (extended << 8) | in.read();
            }
            length = (int) extended;
        }

        int read = 0;
        final byte[] payload = new byte[Math.max(length, 1)];
        while (read < length) {
            final int n = in.read(payload, 0, Math.min(payload.length, length - read));
            if (n < 0) {
                return -1;
            }
            read += n;
        }
        return length;
    }

    private void write(final byte[] bytes) throws IOException {
        final OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    private String readHead() throws IOException {
        final InputStream in = socket.getInputStream();
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
        return head.toString(StandardCharsets.US_ASCII.name());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
