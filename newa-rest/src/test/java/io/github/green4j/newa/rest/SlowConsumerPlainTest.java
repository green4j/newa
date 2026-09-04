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

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * An ordinary response - rendered whole and written as one message - whose peer takes it a byte at a time.
 * Nothing counted it before: a chunked response had its chunks counted and a file its bytes, and this one had
 * only the idle timeout, which sees a buffer moving and calls the connection busy.
 * <p>
 * There is no way to watch a buffer being drained from outside the channel, so this one is judged whole - at
 * the same rate, which is why the window it gets is its size in units. Both halves of that are here: the
 * trickle runs out of it, and a peer which keeps up is never near it.
 */
class SlowConsumerPlainTest {
    private static final String HOST = "127.0.0.1";

    /** Larger than any socket buffer between the two, so that the write cannot simply land. */
    private static final int BODY_BYTES = 1024 * 1024;

    /** With a 64K unit that is sixteen windows for the whole response, and four seconds of test. */
    private static final int DEADLINE_MS = 250;

    private static final int TINY_RECEIVE_BUFFER = 4 * 1024;
    private static final int TRICKLE_PAUSE_MS = 50;
    private static final int TRICKLE_READS = 100; // five seconds of them, past the whole allowance

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static RestApi buildApi() {
        final StringBuilder body = new StringBuilder(BODY_BYTES);
        for (int i = 0; i < BODY_BYTES; i++) {
            body.append('a');
        }
        final String value = body.toString();

        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.getJson("/big", (context, output) -> output.stringValue(value));
        return builder.build();
    }

    private void startServing() throws Exception {
        server = RestServer.of(buildApi())
                .withIdleTimeoutMs(0) // so that whatever closes a connection here can only be the deadline
                .withResponseDeadlineMs(DEADLINE_MS)
                .start(0);
    }

    private Socket ask() throws IOException {
        final Socket socket = new Socket();
        socket.setReceiveBufferSize(TINY_RECEIVE_BUFFER); // before the connect, or the window is negotiated
        socket.connect(new InetSocketAddress(HOST, server.port()));
        socket.setSoTimeout(30_000);

        socket.getOutputStream().write(
                "GET /v1/big HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        Assertions.assertTrue(readHead(socket).startsWith("HTTP/1.1 200 OK"));
        return socket;
    }

    @Test
    public void aTrickleReaderIsClosed() throws Exception {
        startServing();

        try (Socket socket = ask()) {
            final InputStream in = socket.getInputStream();

            for (int i = 0; i < TRICKLE_READS && in.read() >= 0; i++) {
                Thread.sleep(TRICKLE_PAUSE_MS);
            }

            // and then as fast as it comes: what the peer had buffered arrives either way, so where the
            // response ends is what says whether it was given up on
            Assertions.assertTrue(drain(in) < BODY_BYTES, "a response taken a byte at a time ran to the end");
        }
    }

    @Test
    public void aReaderWhichKeepsUpGetsTheWholeOfIt() throws Exception {
        startServing();

        try (Socket socket = ask()) {
            Assertions.assertTrue(drain(socket.getInputStream()) >= BODY_BYTES,
                    "a response was cut off from a peer which was taking it");
        }
    }

    /**
     * @param in to read to its end, as fast as it comes.
     * @return how much arrived before the connection ended, however it ended.
     * @throws IOException if the socket does something other than closing.
     */
    private static int drain(final InputStream in) throws IOException {
        final byte[] bite = new byte[64 * 1024];
        int read = 0;
        try {
            while (read < BODY_BYTES) {
                final int n = in.read(bite);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (final SocketException closedUnderIt) {
            return read; // a peer which closes with bytes still unread sends an RST, and this is that
        }
        return read;
    }

    private static String readHead(final Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        final StringBuilder head = new StringBuilder();

        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
            if (b == '\r') {
                matched = matched == 2 ? 3 : 1;
            } else if (b == '\n') {
                matched = matched == 1 ? 2 : (matched == 3 ? 4 : 0);
            } else {
                matched = 0;
            }
        }
        return head.toString();
    }
}
