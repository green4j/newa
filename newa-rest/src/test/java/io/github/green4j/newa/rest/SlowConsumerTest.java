/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.rest.files.FileServer;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 * A response whose peer takes it a byte at a time. Nothing here is idle - the bytes keep moving, and every
 * timer which asks only whether they moved sees a healthy transfer - so what is on the line is a buffer, or
 * a descriptor and an open file, held for as long as the peer likes.
 * <p>
 * Both response forms are judged by one rule and so are asked the same two questions here: an ordinary
 * response, rendered whole and written as one message, which is judged at its size in 64K units; and a file
 * sent from the page cache, whose region renews its window per unit transferred. Each keeps the sizes and
 * the timings it was found to need.
 * <p>
 * Both ends have their socket buffers pinned small, and that is what makes the test a test: a kernel whose
 * send buffer can swallow the whole response completes the write the instant it takes the bytes, and a
 * response nobody is owed is on no clock at all. Linux autotunes that buffer into the megabytes, so leaving
 * it to the machine is how this passes at one desk and fails on another.
 */
class SlowConsumerTest {
    private static final String HOST = "127.0.0.1";
    private static final int TINY_RECEIVE_BUFFER = 4 * 1024;
    private static final int TINY_SEND_BUFFER = 32 * 1024;
    private static final int TRICKLE_PAUSE_MS = 50;
    private static final int BITE = 64 * 1024;

    /**
     * The two forms of response, each with the size and the window it needs to say anything: the body has
     * to be larger than any socket buffer between the two ends, or the write simply lands and there is
     * nothing slow about it.
     */
    private enum Body {
        /** Rendered whole and written as one message. With a 64K unit, 1M is sixteen windows of it. */
        ORDINARY_RESPONSE(1024 * 1024, 250, 100, 0),
        /** Sent from the page cache as a region, which renews its window per unit transferred. */
        FILE(16 * 1024 * 1024, 500, 40, 512 * 1024);

        private final int bytes;
        private final int deadlineMs;
        private final int trickleReads;
        /** How often a peer which is keeping up pauses, or 0 for one which never does. */
        private final int keepingUpPauseEvery;

        Body(final int bytes,
                final int deadlineMs,
                final int trickleReads,
                final int keepingUpPauseEvery) {
            this.bytes = bytes;
            this.deadlineMs = deadlineMs;
            this.trickleReads = trickleReads;
            this.keepingUpPauseEvery = keepingUpPauseEvery;
        }
    }

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

    private String startServing(final Body body) throws Exception {
        if (body == Body.ORDINARY_RESPONSE) {
            final StringBuilder content = new StringBuilder(body.bytes);
            for (int i = 0; i < body.bytes; i++) {
                content.append('a');
            }
            final String value = content.toString();

            final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
            builder.getJson("/big", (context, output) -> output.stringValue(value));

            server = RestServer.of(builder.build())
                    .withIdleTimeoutMs(0) // so that whatever closes a connection here can only be the deadline
                    .withResponseDeadlineMs(body.deadlineMs)
                    .start(trickleBootstrap());
            return "/v1/big";
        }

        final Path file = filesRoot.resolve("big.bin");
        final byte[] block = new byte[512 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int written = 0; written < body.bytes; written += block.length) {
                out.write(block);
            }
        }

        server = FileServer.of(FileSet.builder().serve("/files", filesRoot).build())
                .withIdleTimeoutMs(0)
                .withResponseDeadlineMs(body.deadlineMs)
                .start(trickleBootstrap());
        return "/files/big.bin";
    }

    /**
     * @return an ephemeral port whose accepted connections cannot hide a response in the kernel.
     */
    private static NettyServerBuilder trickleBootstrap() {
        return new NettyServerBuilder()
                .port(0)
                .childOption(ChannelOption.SO_SNDBUF, TINY_SEND_BUFFER);
    }

    private Socket ask(final String path) throws IOException {
        final Socket socket = new Socket();
        socket.setReceiveBufferSize(TINY_RECEIVE_BUFFER); // before the connect, or the window is negotiated
        socket.connect(new InetSocketAddress(HOST, server.port()));
        socket.setSoTimeout(30_000);

        socket.getOutputStream().write(
                ("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        Assertions.assertTrue(readHead(socket).startsWith("HTTP/1.1 200 OK"));
        return socket;
    }

    @ParameterizedTest
    @EnumSource(Body.class)
    public void aTrickleReaderIsClosed(final Body body) throws Exception {
        final String path = startServing(body);

        try (Socket socket = ask(path)) {
            final InputStream in = socket.getInputStream();

            int trickled = 0;
            for (int i = 0; i < body.trickleReads && in.read() >= 0; i++) {
                trickled++;
                Thread.sleep(TRICKLE_PAUSE_MS);
            }

            // and then as fast as it comes. What the peer had buffered arrives either way - a connection
            // closed under a trickle is noticed after it, not instead of it - so what says whether the
            // response was given up on is where it ends
            final int read = trickled + drain(in, body.bytes - trickled);

            Assertions.assertTrue(read < body.bytes,
                    "a response taken a byte at a time ran to the end");
        }
    }

    @ParameterizedTest
    @EnumSource(Body.class)
    public void aReaderWhichKeepsUpGetsTheWholeOfIt(final Body body) throws Exception {
        // the other side of the same rule: what is judged is the rate, and a peer which is merely not
        // instant clears it several times over
        final String path = startServing(body);

        try (Socket socket = ask(path)) {
            final InputStream in = socket.getInputStream();
            final byte[] bite = new byte[BITE];

            int read = 0;
            int pauseAt = body.keepingUpPauseEvery;
            while (read < body.bytes) {
                final int n = in.read(bite, 0, Math.min(bite.length, body.bytes - read));
                if (n < 0) {
                    break;
                }
                read += n;
                if (body.keepingUpPauseEvery > 0 && read >= pauseAt) {
                    Thread.sleep(25);
                    pauseAt = read + body.keepingUpPauseEvery;
                }
            }

            Assertions.assertEquals(body.bytes, read, "the transfer was cut off");
        }
    }

    /**
     * @param in     to read to its end, as fast as it comes.
     * @param length expected of the whole of it.
     * @return how much arrived before the connection ended, however it ended.
     * @throws IOException if the socket does something other than closing.
     */
    private static int drain(final InputStream in,
                             final int length) throws IOException {
        final byte[] bite = new byte[BITE];
        int read = 0;
        try {
            while (read < length) {
                final int n = in.read(bite, 0, Math.min(bite.length, length - read));
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
