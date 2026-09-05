/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * A websocket client written out on a plain socket, for the tests which are about the bytes rather than
 * about the messages.
 * <p>
 * The JDK's own client cannot serve them: it refuses to set {@code Origin}, which is a restricted header,
 * and it decides for itself how a large message is cut into frames - so neither the origin tests nor the
 * frame size ones could say what they mean through it.
 */
final class RawWebSocket implements AutoCloseable {
    static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    static final int CONTINUATION = 0x0;
    static final int TEXT = 0x1;
    static final int BINARY = 0x2;

    private static final Random MASKS = new Random(20260903L);

    private final Socket socket;

    RawWebSocket(final String host,
                 final int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(10_000);
    }

    /**
     * @param path    to ask for.
     * @param headers extra ones, as whole lines without the terminator.
     * @return the response head, up to and without the empty line, or an empty string if the connection was
     *         closed with nothing said.
     * @throws IOException if the socket does.
     */
    String handshake(final String path,
                     final String... headers) throws IOException {
        final StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(socket.getInetAddress().getHostAddress())
                .append(':').append(socket.getPort()).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(KEY).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        for (int i = 0; i < headers.length; i++) {
            request.append(headers[i]).append("\r\n");
        }
        request.append("\r\n");

        write(request.toString().getBytes(StandardCharsets.US_ASCII));

        return readHead();
    }

    /**
     * @param payload to send as one masked text frame, as a client must.
     * @throws IOException if the socket does.
     */
    void sendText(final byte[] payload) throws IOException {
        sendFrame(TEXT, true, payload);
    }

    /**
     * @param opcode of the frame - {@link #TEXT}, {@link #BINARY} or {@link #CONTINUATION}.
     * @param fin whether the message ends with this frame.
     * @param payload to send as one masked frame, as a client must.
     * @throws IOException if the socket does.
     */
    void sendFrame(final int opcode,
                   final boolean fin,
                   final byte[] payload) throws IOException {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((fin ? 0x80 : 0x00) | opcode);

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
     * @return the opcode of the next frame and, for a close frame, the status it carries, or -1 for either
     *         if the connection ended first.
     * @throws IOException if the socket does.
     */
    int[] readFrame() throws IOException {
        final InputStream in = socket.getInputStream();

        final int first = in.read();
        if (first < 0) {
            return new int[] {-1, -1};
        }
        final int opcode = first & 0x0F;

        int length = in.read() & 0x7F; // a server never masks, so the top bit is not one
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            long extended = 0;
            for (int i = 0; i < 8; i++) {
                extended = (extended << 8) | in.read();
            }
            length = (int) extended; // nothing these tests send is anywhere near what would not fit
        }

        final byte[] payload = new byte[length];
        int read = 0;
        while (read < length) {
            final int n = in.read(payload, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }

        final int status = opcode == 0x8 && read >= 2
                ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                : -1;

        return new int[] {opcode, status};
    }

    /**
     * Reads whatever is left until the peer closes the connection. Netty says goodbye more than once - a
     * refused frame is answered with the close status the decoder chose and then with the one the protocol
     * handler is configured to send - so a test which wants to know that the connection went cannot simply
     * expect the next byte to be the end of the stream.
     *
     * @return whether the end of the stream was reached; the socket's own timeout is what bounds the wait.
     * @throws IOException if the socket does.
     */
    boolean awaitClose() throws IOException {
        final InputStream in = socket.getInputStream();
        while (in.read() >= 0) {
            continue;
        }
        return true;
    }

    void write(final byte[] bytes) throws IOException {
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

        final String whole = head.toString(StandardCharsets.US_ASCII.name());
        return whole.substring(0, whole.length() - 4);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
