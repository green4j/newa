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



package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A transfer whose peer takes it a byte at a time. Nothing here is idle - the bytes keep moving, and every
 * timer which asks only whether they moved sees a healthy transfer - so what is on the line is a descriptor,
 * an open file and a queued region held for as long as the peer likes.
 */
class SlowConsumerFileTest {
    private static final String HOST = "127.0.0.1";

    /** Large enough that the kernel cannot swallow it whole: a write which fits the socket buffer lands. */
    private static final int BIG_FILE_BYTES = 16 * 1024 * 1024;

    private static final int DEADLINE_MS = 500;
    private static final int TINY_RECEIVE_BUFFER = 4 * 1024;

    private static final int TRICKLE_PAUSE_MS = 50;
    private static final int TRICKLE_READS = 40; // two seconds of them, four windows

    private static final int KEEPING_UP_BITE = 64 * 1024;
    private static final int KEEPING_UP_PAUSE_EVERY = 512 * 1024;
    private static final long KEEPING_UP_PAUSE_MS = 25;

    private NettyServer server;

    @TempDir
    private Path filesRoot;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private void startServing() throws Exception {
        final Path file = filesRoot.resolve("big.bin");
        final byte[] block = new byte[KEEPING_UP_PAUSE_EVERY];
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int written = 0; written < BIG_FILE_BYTES; written += block.length) {
                out.write(block);
            }
        }

        server = FileServer.of(FileSet.builder().serve("/files", filesRoot).build())
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
                "GET /files/big.bin HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
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

            // and then as fast as it comes. What the peer had buffered arrives either way - a connection
            // closed under a trickle is noticed after it, not instead of it - so what says whether the
            // transfer was given up on is where it ends
            Assertions.assertTrue(drain(in) < BIG_FILE_BYTES, "a transfer taken a byte at a time ran to the end");
        }
    }

    @Test
    public void aReaderWhichKeepsUpIsNotCut() throws Exception {
        // the other side of the same rule: what is judged is the rate, and a peer which is merely not instant
        // clears it several times over
        startServing();

        try (Socket socket = ask()) {
            final InputStream in = socket.getInputStream();
            final byte[] bite = new byte[KEEPING_UP_BITE];

            int read = 0;
            int pauseAt = KEEPING_UP_PAUSE_EVERY;
            while (read < BIG_FILE_BYTES) {
                final int n = in.read(bite, 0, Math.min(bite.length, BIG_FILE_BYTES - read));
                if (n < 0) {
                    break;
                }
                read += n;
                if (read >= pauseAt) {
                    Thread.sleep(KEEPING_UP_PAUSE_MS);
                    pauseAt = read + KEEPING_UP_PAUSE_EVERY;
                }
            }

            Assertions.assertEquals(BIG_FILE_BYTES, read, "the transfer was cut off");
        }
    }

    /**
     * @param in to read to its end, as fast as it comes.
     * @return how much arrived before the connection ended, however it ended.
     * @throws IOException if the socket does something other than closing.
     */
    private static int drain(final InputStream in) throws IOException {
        final byte[] bite = new byte[KEEPING_UP_BITE];
        int read = 0;
        try {
            while (read < BIG_FILE_BYTES) {
                final int n = in.read(bite, 0, Math.min(bite.length, BIG_FILE_BYTES - read));
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
