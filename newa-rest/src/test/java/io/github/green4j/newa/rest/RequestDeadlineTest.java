/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */



package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * The bound an idle timeout cannot be. Every test here runs with the idle timeout switched off, so that
 * whatever closes a connection can only be the deadline - and a peer which dribbles a request out is never
 * idle in the first place.
 * <p>
 * What the handler itself does - a connection which asks nothing, a keep-alive connection between requests,
 * a deadline which no amount of arriving bytes extends - is pinned deterministically on a virtual clock in
 * {@code RequestDeadlineHandlerTest} over in newa-common. What is left here is what only a real server can
 * show: that the handler is in the pipeline RestServer builds, and that the two settings reach it.
 */
class RequestDeadlineTest {
    private static final String HOST = "127.0.0.1";

    private static final int DEADLINE_MS = 250;
    private static final long PAST_IT_MS = DEADLINE_MS * 4L;

    private static final String REQUEST = "GET /v1/hello/world HTTP/1.1\r\nHost: x\r\n\r\n";

    private NettyServer server;

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

    private void startWith(final int requestDeadlineMs) throws InterruptedException {
        server = RestServer.of(buildApi())
                .withIdleTimeoutMs(0) // so that nothing but the deadline can close anything here
                .withRequestDeadlineMs(requestDeadlineMs)
                .start(0);
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket(HOST, server.port());
        socket.setSoTimeout(10_000);
        return socket;
    }

    @Test
    public void theDefaultIsHalfTheIdleTimeout() {
        Assertions.assertEquals(RestServer.DEFAULT_IDLE_TIMEOUT_MS / 2, RestServer.DEFAULT_DEADLINE_MS);
    }


    @Test
    public void aRequestDribbledOutIsClosed() throws Exception {
        startWith(DEADLINE_MS);

        try (Socket socket = connect()) {
            Assertions.assertTrue(dribble(socket), "a request dribbled out a byte at a time was let through");
        }
    }



    @Test
    public void zeroLetsARequestArriveAsSlowlyAsItLikes() throws Exception {
        startWith(0);

        try (Socket socket = connect()) {
            final OutputStream out = socket.getOutputStream();
            final byte[] request = REQUEST.getBytes(StandardCharsets.US_ASCII);

            out.write(request, 0, 4);
            out.flush();
            Thread.sleep(PAST_IT_MS);
            out.write(request, 4, request.length - 4);
            out.flush();

            Assertions.assertTrue(readOneResponse(socket).startsWith("HTTP/1.1 200 OK"),
                    "a request which was allowed to take its time was cut off");
        }
    }


    /**
     * Sends a request a byte at a time, a quarter of the deadline apart, until it is sent four times over or
     * the connection goes.
     *
     * @param socket to dribble into.
     * @return whether the connection was closed before the request had been sent.
     * @throws IOException          if the socket does, which is that same finding said by the write.
     * @throws InterruptedException if a pause is cut short.
     */
    private static boolean dribble(final Socket socket) throws IOException, InterruptedException {
        final OutputStream out = socket.getOutputStream();
        final byte[] request = REQUEST.getBytes(StandardCharsets.US_ASCII);

        try {
            for (int i = 0; i < request.length * 4; i++) {
                out.write(request[i % request.length]);
                out.flush();
                Thread.sleep(DEADLINE_MS / 4);
            }
        } catch (final IOException closedUnderIt) {
            return true;
        }
        return awaitClose(socket);
    }

    /**
     * @param socket to read out.
     * @return whether the peer closed it; whatever it had left to say is read and dropped on the way.
     * @throws IOException if the socket does.
     */
    private static boolean awaitClose(final Socket socket) throws IOException {
        socket.setSoTimeout((int) PAST_IT_MS);
        try {
            final InputStream in = socket.getInputStream();
            while (in.read() >= 0) {
                continue;
            }
            return true;
        } catch (final SocketTimeoutException stillOpen) {
            return false;
        }
    }

    private static String readOneResponse(final Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        final StringBuilder response = new StringBuilder();

        int contentLength = -1;
        int matched = 0;
        while (matched < 4) { // the head, up to and including the empty line
            final int b = in.read();
            if (b < 0) {
                return response.toString();
            }
            response.append((char) b);
            if (b == '\r') {
                matched = matched == 2 ? 3 : 1;
            } else if (b == '\n') {
                matched = matched == 1 ? 2 : (matched == 3 ? 4 : 0);
            } else {
                matched = 0;
            }
        }

        final String head = response.toString();
        final int at = head.toLowerCase().indexOf("content-length:");
        if (at >= 0) {
            contentLength = Integer.parseInt(head.substring(at + 15, head.indexOf("\r\n", at)).trim());
        }
        for (int i = 0; i < contentLength; i++) {
            final int b = in.read();
            if (b < 0) {
                break;
            }
            response.append((char) b);
        }
        return response.toString();
    }
}
