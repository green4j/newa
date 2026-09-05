/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The connection timer at a real server. What the handler itself does - closing a connection which says
 * nothing, and leaving alone one which is read from or written to - is pinned deterministically on a
 * virtual clock in {@code IdleConnectionHandlerTest} over in newa-common. What is here is what only a real
 * server can show: where the default sits, that a keep-alive connection is reclaimed once its client walks
 * away, that a transfer still moving is not idle, and that zero means no timer at all.
 */
class IdleTimeoutTest {
    private static final String HOST = "127.0.0.1";

    private static final int IDLE_MS = 250;
    private static final long PAST_IT_MS = IDLE_MS * 4L;

    // large enough that the kernel cannot swallow it whole: a write which fits entirely in the socket
    // buffer completes at once, and then the connection is idle by any measure and this proves nothing
    private static final int BIG_FILE_BYTES = 16 * 1024 * 1024;
    private static final int SLOW_READ_BYTES = 64 * 1024;
    private static final int SLOW_READ_PAUSE_EVERY = 512 * 1024;
    private static final long SLOW_READ_PAUSE_MS = 25;

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

    private static RestApi buildApi() {
        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.getJson("/hello/{name}",
                (context, output) ->
                        output.stringValue("Hello " + context.pathParameters().valueRequired("name") + "!"))
                .withPathParameterDescriptions("name - who to greet");
        return builder.build();
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket(HOST, server.port());
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void ask(final Socket socket) throws IOException {
        // no "Connection: close": this is the keep-alive connection which is meant to be reused, and which
        // nothing would ever take back if the client stopped reusing it
        socket.getOutputStream().write(
                "GET /v1/hello/world HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /**
     * @param socket to read out.
     * @return whether the peer closed it; whatever it had left to say is read and dropped on the way.
     * @throws IOException if the socket does.
     */
    private static boolean awaitClose(final Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        while (in.read() >= 0) {
            continue;
        }
        return true;
    }

    private static void assertStillOpen(final Socket socket) throws IOException {
        socket.setSoTimeout((int) PAST_IT_MS);
        try {
            Assertions.assertNotEquals(-1, socket.getInputStream().read(), "the connection was closed");
        } catch (final SocketTimeoutException nothingCameAndNothingClosed) {
            return; // which is the pass: the wait ran out rather than the connection
        }
    }

    @Test
    public void theDefaultIsAMinute() {
        Assertions.assertEquals(60_000, RestServer.DEFAULT_IDLE_TIMEOUT_MS);
    }

    @Test
    public void itStaysAboveWhatJudgesEitherEndOfTheConnection() {
        // the idle timeout knows only that bytes moved. Whether a request is arriving too slowly, or a peer
        // is taking a response too slowly, is answered by the pair which counts what actually arrived - and
        // those have to be the ones which fire, or a coarser instrument takes their decisions over
        Assertions.assertTrue(
                RestServer.DEFAULT_IDLE_TIMEOUT_MS > RestServer.DEFAULT_DEADLINE_MS,
                "a deadline would be pre-empted by the connection timer standing above it");
    }


    @Test
    public void aKeepAliveConnectionIsClosedOnceItGoesQuiet() throws Exception {
        server = RestServer.of(buildApi())
                .withIdleTimeoutMs(IDLE_MS)
                .start(0);

        try (Socket socket = connect()) {
            ask(socket);

            // the answer is read out, and then nobody asks anything again - which is the client that walked
            // away, and the file descriptor nothing else would take back
            Assertions.assertTrue(awaitClose(socket), "the connection was left open");
        }
    }


    @Test
    public void zeroHoldsAConnectionWhichSaysNothing() throws Exception {
        server = RestServer.of(buildApi())
                .withIdleTimeoutMs(0)
                .start(0);

        try (Socket socket = connect()) {
            assertStillOpen(socket);

            // and it is still there to be asked, which is what having no timer means
            ask(socket);
            Assertions.assertTrue(readOneResponse(socket).startsWith("HTTP/1.1 200 OK"));
        }
    }

    @Test
    public void aSlowButMovingTransferIsNotIdle() throws Exception {
        // the case which decides how write idleness is measured. One file to one slow peer is a single
        // write which completes at the end, so a timer waiting for that completion would cut the transfer
        // off in the middle of itself. What is watched instead is the outbound buffer making progress
        final Path file = bigFile();

        final FileSet files = FileSet.builder().serve("/files", filesRoot).build();

        server = RestServer.of(buildApi())
                .withHandler(() -> new FileServerHandler(files))
                .withIdleTimeoutMs(IDLE_MS)
                .start(0);

        try (Socket socket = connect()) {
            socket.getOutputStream().write(
                    "GET /files/big.bin HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            final String head = readHead(socket);
            Assertions.assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);
            Assertions.assertEquals(BIG_FILE_BYTES, contentLengthOf(head), head);

            final long startedAt = System.nanoTime();
            final int read = readSlowly(socket, BIG_FILE_BYTES);
            final long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;

            Assertions.assertEquals(BIG_FILE_BYTES, read, "the transfer was cut off");
            // and it did take longer than the timeout, so the assertion above was about something
            Assertions.assertTrue(tookMs > IDLE_MS, "too fast to have proven anything: " + tookMs + "ms");
        }
    }

    /**
     * @param socket to read from in small bites, pausing between them so the read outlives the timeout
     *               while never quite stopping.
     * @param length expected.
     * @return how much arrived before the connection ended.
     * @throws IOException          if the socket does.
     * @throws InterruptedException if the pause is cut short.
     */
    private static int readSlowly(final Socket socket,
                                  final int length) throws IOException, InterruptedException {
        final InputStream in = socket.getInputStream();
        final byte[] bite = new byte[SLOW_READ_BYTES];

        int read = 0;
        int pauseAt = SLOW_READ_PAUSE_EVERY;
        while (read < length) {
            final int n = in.read(bite, 0, Math.min(bite.length, length - read));
            if (n < 0) {
                break;
            }
            read += n;
            if (read >= pauseAt) {
                // the pauses are what make this outlive the timeout: enough of them to be sure, and never
                // one long enough to be a peer which has genuinely stopped taking anything
                Thread.sleep(SLOW_READ_PAUSE_MS);
                pauseAt = read + SLOW_READ_PAUSE_EVERY;
            }
        }
        return read;
    }

    private Path bigFile() throws IOException {
        final Path file = filesRoot.resolve("big.bin");
        final byte[] block = new byte[SLOW_READ_PAUSE_EVERY];
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int written = 0; written < BIG_FILE_BYTES; written += block.length) {
                out.write(block);
            }
        }
        return file;
    }

    /**
     * @param socket to read one whole response from, head and body, so that the next one starts where it
     *               ought to.
     * @return the head, as it came.
     * @throws IOException if the socket does.
     */
    private static String readOneResponse(final Socket socket) throws IOException {
        final String head = readHead(socket);
        final InputStream in = socket.getInputStream();

        final int length = contentLengthOf(head);
        for (int i = 0; i < length; i++) {
            Assertions.assertTrue(in.read() >= 0, "the body was shorter than it said");
        }
        return head;
    }

    private static String readHead(final Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        final StringBuilder head = new StringBuilder();

        int matched = 0; // how much of the empty line which ends a head has been seen
        while (matched < 4) {
            final int b = in.read();
            Assertions.assertTrue(b >= 0, "the connection ended mid-response");
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

    private static int contentLengthOf(final String head) {
        final String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            final String line = lines[i];
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                return Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        return 0;
    }
}
