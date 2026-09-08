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
import io.github.green4j.newa.server.SingleHttpExchangeHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What a real client sees when it does what HTTP/1.1 allows: sends the next request without waiting for the
 * answer to the previous one. {@code SingleHttpExchangeHandlerTest} in {@code newa-common} pins the handler
 * itself; this is the server, over a socket, with every request in one write so they reach the codec in one
 * read - and, first of all, that every server of this module has the handler at all.
 * <p>
 * The api here answers late on purpose. A handle which answers where it stands finishes its exchange inside
 * the same read, so the requests behind it are ordinary ones by the time they are seen and nothing is ever
 * held - which is correct, and which is why holding cannot be shown with one.
 */
class PipeliningTest {
    private static final String HOST = "127.0.0.1";
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int ANSWER_DELAY_MILLIS = 300;

    private static String request(final String name) {
        return "GET /v1/hello/" + name + " HTTP/1.1\r\nHost: x\r\n\r\n";
    }

    @TempDir
    Path directory;

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /**
     * @return an api whose only handle answers after the read which brought the request is long over.
     */
    private static RestApi slowApi() {
        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.get("/hello/{name}", (context, result) -> {
            final byte[] body = ('"' + context.pathParameters().valueRequired("name") + '"')
                    .getBytes(StandardCharsets.US_ASCII);
            context.executor().schedule(
                    () -> result.ok(HttpHeaderValues.APPLICATION_JSON, body, 0, body.length),
                    ANSWER_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }).withPathParameterDescriptions("name - who to greet");
        return builder.build();
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket(HOST, server.port());
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return socket;
    }

    private static void send(final Socket socket,
                             final String... names) throws IOException {
        final StringBuilder requests = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            requests.append(request(names[i]));
        }
        final OutputStream out = socket.getOutputStream();
        out.write(requests.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readUntil(final InputStream in,
                                    final String marker) throws IOException {
        final StringBuilder answered = new StringBuilder();
        while (answered.indexOf(marker) < 0) {
            final int b = in.read();
            if (b < 0) {
                break;
            }
            answered.append((char) b);
        }
        return answered.toString();
    }

    private static String readToEnd(final InputStream in) throws IOException {
        final StringBuilder answered = new StringBuilder();
        int b = in.read();
        while (b >= 0) {
            answered.append((char) b);
            b = in.read();
        }
        return answered.toString();
    }

    @Test
    public void everyServerHasTheExchangeGate() throws IOException {
        final Path file = directory.resolve("file.txt");
        Files.writeString(file, "file");

        final EmbeddedChannel rest =
                new EmbeddedChannel(RestServer.of(request -> null).pipeline());
        final EmbeddedChannel files = new EmbeddedChannel(
                FileServer.of(FileSet.builder().file("/file", file).build()).pipeline()
        );

        // no memory budget anywhere here: the invariant belongs to the server, not to the accounting
        Assertions.assertNotNull(rest.pipeline().get(SingleHttpExchangeHandler.class));
        Assertions.assertNotNull(files.pipeline().get(SingleHttpExchangeHandler.class));

        rest.finishAndReleaseAll();
        files.finishAndReleaseAll();
    }

    @Test
    public void aStartedServerHasTheExchangeGate() throws Exception {
        final CountDownLatch initialized = new CountDownLatch(1);
        final boolean[] gatePresent = new boolean[1];
        final RestServer rest = RestServer.of(request -> null)
                .withHandler(() -> new ChannelInboundHandlerAdapter() {
                    @Override
                    public void handlerAdded(final ChannelHandlerContext ctx) {
                        gatePresent[0] =
                                ctx.pipeline().get(SingleHttpExchangeHandler.class) != null;
                        initialized.countDown();
                    }
                });

        server = rest.start(new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1));
        try (Socket connection = new Socket(HOST, server.port())) {
            Assertions.assertTrue(initialized.await(10, TimeUnit.SECONDS));
            Assertions.assertTrue(gatePresent[0]);
            Assertions.assertTrue(connection.isConnected());
        }
    }

    @Test
    public void aPipelinedRequestIsAnsweredAfterTheOneInFront() throws Exception {
        server = RestServer.of(slowApi()).start(0);

        try (Socket socket = connect()) {
            send(socket, "first", "second");

            final String answered = readUntil(socket.getInputStream(), "\"second\"");

            final int first = answered.indexOf("\"first\"");
            final int second = answered.indexOf("\"second\"");
            Assertions.assertTrue(first >= 0, "The first answer never came: " + answered);
            Assertions.assertTrue(second > first, "The answers were out of order: " + answered);
            Assertions.assertEquals(2, countOf(answered, "HTTP/1.1 200"), answered);
        }
    }

    @Test
    public void aThirdPipelinedRequestIsMoreThanTheConnectionWillHold() throws Exception {
        server = RestServer.of(slowApi()).start(0);

        try (Socket socket = connect()) {
            send(socket, "first", "second", "third");

            // whatever was answered before the connection went, the third was never one of them
            final String answered = readToEnd(socket.getInputStream());

            Assertions.assertFalse(answered.contains("\"third\""), answered);
        }
    }

    @Test
    public void aKeepAliveConnectionStillServesOneRequestAfterAnother() throws Exception {
        server = RestServer.of(slowApi()).start(0);

        try (Socket socket = connect()) {
            send(socket, "first");
            Assertions.assertTrue(
                    readUntil(socket.getInputStream(), "\"first\"").contains("HTTP/1.1 200"));

            send(socket, "second");
            Assertions.assertTrue(
                    readUntil(socket.getInputStream(), "\"second\"").contains("HTTP/1.1 200"));
        }
    }

    private static int countOf(final String text,
                               final String what) {
        int count = 0;
        int at = text.indexOf(what);
        while (at >= 0) {
            count++;
            at = text.indexOf(what, at + what.length());
        }
        return count;
    }
}
