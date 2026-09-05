/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.server.NettyServer;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

class FileServerTest {
    private static final String HOST = "127.0.0.1";
    private static final String FILE_BODY = "a file, sent from the page cache";
    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    private final HttpClient httpClient = HttpClient.newHttpClient();

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

    @Test
    public void oneLinerServesTheFiles() throws Exception {
        server = FileServer.start(0, buildFiles());

        final HttpResponse<byte[]> response = get("/files/thing.txt");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(FILE_BODY, text(response));
    }

    @Test
    public void aPathNoFileOwnsIsAnsweredRatherThanHeld() throws Exception {
        server = FileServer.start(0, buildFiles());

        final HttpResponse<byte[]> unowned = get("/nothing/here");
        final HttpResponse<byte[]> filtered = get("/files/internal/secret.txt");

        Assertions.assertEquals(404, unowned.statusCode());
        Assertions.assertEquals(
                "application/json; charset=utf-8",
                unowned.headers().firstValue("Content-Type").orElse(null)
        );

        // a path nothing serves and a file which may not be served answer the same, down to the body: the
        // shape of the answer would otherwise say which prefixes are served and which files are kept back
        Assertions.assertEquals(404, filtered.statusCode());
        Assertions.assertEquals(text(filtered), text(unowned));
    }

    @Test
    public void andTheConnectionSurvivesIt() throws Exception {
        // the whole reason FilesOnlyHandler exists: without it the request reaches the end of the pipeline,
        // is discarded in silence, and the connection stays open for as long as the peer keeps it
        server = FileServer.start(0, buildFiles());

        try (Socket socket = new Socket(HOST, server.port())) {
            socket.setSoTimeout(10_000);

            final String notFound = exchange(socket, "/nothing/here");
            Assertions.assertTrue(notFound.startsWith("HTTP/1.1 404 Not Found"), notFound);
            Assertions.assertNull(valueOf(notFound, "Connection"), notFound);

            // and the next request on the very same connection is still answered
            final String file = exchange(socket, "/files/thing.txt");
            Assertions.assertTrue(file.startsWith("HTTP/1.1 200 OK"), file);
        }
    }

    @Test
    public void anApiBehindTheFilesAnswersWhatTheyDoNotOwn() throws Exception {
        server = FileServer.of(buildFiles())
                .withHandler(() -> new RestApiHandler(
                        buildApi(), new JsonErrorHandler(), new StdErrChannelErrorHandler()))
                .start(0);

        Assertions.assertEquals(FILE_BODY, text(get("/files/thing.txt")));

        final HttpResponse<byte[]> api = get("/v1/hello/world");
        Assertions.assertEquals(200, api.statusCode());
        Assertions.assertEquals("\"Hello world!\"", text(api));

        // nothing stands between the file handler and the socket, so the files go out of the page cache
        Assertions.assertEquals("\"zero-copy\"", text(get("/v1/zero-copy")));
    }

    @Test
    public void compressionCompressesAFileAndCostsZeroCopy() throws Exception {
        server = FileServer.of(buildFiles())
                .withCompression()
                .withHandler(() -> new RestApiHandler(
                        buildApi(), new JsonErrorHandler(), new StdErrChannelErrorHandler()))
                .start(0);

        final HttpResponse<byte[]> file = get("/files/thing.txt");
        Assertions.assertEquals(200, file.statusCode());
        Assertions.assertEquals(
                "gzip",
                file.headers().firstValue("Content-Encoding").orElse(null)
        );
        Assertions.assertEquals(FILE_BODY, text(file));

        // that is what it costs: the compressor has to see the bytes, so the file is read into the process
        Assertions.assertEquals("\"pumped\"", text(get("/v1/zero-copy")));
    }

    @Test
    public void corsReachesAFile() throws Exception {
        server = FileServer.of(buildFiles())
                .withCors(CorsConfigBuilder.forOrigin(ALLOWED_ORIGIN)
                        .allowedRequestMethods(HttpMethod.GET, HttpMethod.HEAD)
                        .build())
                .start(0);

        try (Socket socket = new Socket(HOST, server.port())) {
            socket.setSoTimeout(10_000);

            final String head = exchange(socket, "/files/thing.txt", "Origin: " + ALLOWED_ORIGIN);

            Assertions.assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);
            Assertions.assertEquals(
                    ALLOWED_ORIGIN,
                    valueOf(head, "Access-Control-Allow-Origin"),
                    head
            );
        }
    }

    private FileSet buildFiles() throws IOException {
        Files.write(filesRoot.resolve("thing.txt"), FILE_BODY.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(filesRoot.resolve("internal"));
        Files.write(filesRoot.resolve("internal/secret.txt"),
                "not for the wire".getBytes(StandardCharsets.UTF_8));

        return FileSet.builder()
                .serve("/files", filesRoot, PathMask.excluding("internal/**"))
                .build();
    }

    private static RestApi buildApi() {
        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.getJson("/hello/{name}",
                (context, output) ->
                        output.stringValue("Hello " + context.pathParameters().valueRequired("name") + "!"))
                .withPathParameterDescriptions("name - who to greet");
        builder.getJson("/zero-copy",
                (context, output) -> output.stringValue(
                        FileServerHandler.zeroCopySupported(context.channel()) ? "zero-copy" : "pumped"));
        return builder.build();
    }

    private HttpResponse<byte[]> get(final String path) throws Exception {
        return httpClient.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("http://" + HOST + ":" + server.port() + path))
                        .header("Accept-Encoding", "gzip")
                        .GET()
                        .build(),
                BodyHandlers.ofByteArray()
        );
    }

    private static String text(final HttpResponse<byte[]> response) throws IOException {
        if (response.headers().firstValue("Content-Encoding").isPresent()) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * One request written on a connection which is expected to be reused, and the whole response read back
     * off it - the head, and then exactly the body the head promised, so that the socket is left positioned
     * at the start of the next response.
     *
     * @param socket to ask on, and to leave usable
     * @param path to ask for
     * @param headers extra ones, as whole lines without the terminator
     * @return the response head, up to and without the empty line which ends it
     * @throws IOException if the socket does
     */
    private String exchange(final Socket socket,
                            final String path,
                            final String... headers) throws IOException {
        final StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(HOST).append(':').append(server.port()).append("\r\n");
        for (int i = 0; i < headers.length; i++) {
            request.append(headers[i]).append("\r\n");
        }
        request.append("\r\n");

        final OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        final InputStream in = socket.getInputStream();
        final String head = readHead(in);

        final String length = valueOf(head, "Content-Length");
        Assertions.assertNotNull(length, head); // nothing here answers without one
        final byte[] body = new byte[Integer.parseInt(length)];
        int read = 0;
        while (read < body.length) {
            final int n = in.read(body, read, body.length - read);
            Assertions.assertTrue(n > 0, "the connection ended in the middle of a response");
            read += n;
        }
        return head;
    }

    private static String valueOf(final String head,
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
