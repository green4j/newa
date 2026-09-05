/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

class RestServerTest {
    private static final String HOST = "127.0.0.1";
    private static final String FILE_BODY = "a file, served in front of the api";

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

    private static RestApi buildApi() {
        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.getJson("/hello/{name}",
                (context, output) ->
                        output.stringValue("Hello " + context.pathParameters().valueRequired("name") + "!"))
                .withPathParameterDescriptions("name - who to greet");
        builder.postJson("/echo", (context, output) -> output.stringValue("posted"));
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

    private Path writeFile() throws IOException {
        final Path file = filesRoot.resolve("thing.txt");
        Files.write(file, FILE_BODY.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void oneLinerServesTheApi() throws Exception {
        server = RestServer.start(buildApi(), 0);

        final HttpResponse<byte[]> response = get("/v1/hello/world");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("\"Hello world!\"", text(response));
    }

    @Test
    public void everyConnectionGetsItsOwnHandler() throws Exception {
        // RestApiHandler is not @Sharable, so one instance reused across channels would fail on the second
        server = RestServer.start(buildApi(), 0);

        Assertions.assertEquals(200, get("/v1/hello/one").statusCode());
        Assertions.assertEquals(200, get("/v1/hello/two").statusCode());
        Assertions.assertEquals(200, get("/v1/hello/three").statusCode());
    }

    @Test
    public void compressionIsOffByDefault() throws Exception {
        server = RestServer.start(buildApi(), 0);

        Assertions.assertTrue(get("/v1/hello/world").headers().firstValue("Content-Encoding").isEmpty());
    }

    @Test
    public void compressionIsOptIn() throws Exception {
        server = RestServer.of(buildApi()).withCompression().start(0);

        final HttpResponse<byte[]> response = get("/v1/hello/world");

        Assertions.assertEquals(
                "gzip",
                response.headers().firstValue("Content-Encoding").orElse(null)
        );
        Assertions.assertEquals("\"Hello world!\"", text(response));
    }

    @Test
    public void filesAreServedInFrontOfTheApiAndStayUncompressed() throws Exception {
        writeFile();

        final FileSet files = FileSet.builder().serve("/files", filesRoot).build();

        server = RestServer.of(buildApi())
                .withHandler(() -> new FileServerHandler(files))
                .withCompression()
                .start(0);

        final HttpResponse<byte[]> file = get("/files/thing.txt");
        Assertions.assertEquals(200, file.statusCode());
        // the compressor sits behind everything withHandler added, so it never sees a file - which is
        // what keeps sendfile(2) available for a file handler put there
        Assertions.assertTrue(file.headers().firstValue("Content-Encoding").isEmpty());
        Assertions.assertEquals(FILE_BODY, new String(file.body(), StandardCharsets.UTF_8));

        final HttpResponse<byte[]> api = get("/v1/hello/world");
        Assertions.assertEquals(200, api.statusCode());
        Assertions.assertEquals("\"Hello world!\"", text(api));
    }

    @Test
    public void maxContentLengthIsHonoured() throws Exception {
        server = RestServer.of(buildApi()).withMaxContentLength(64).start(0);

        final HttpResponse<byte[]> response = httpClient.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("http://" + HOST + ":" + server.port() + "/v1/echo"))
                        .POST(BodyPublishers.ofByteArray(new byte[1024]))
                        .build(),
                BodyHandlers.ofByteArray()
        );

        Assertions.assertEquals(
                HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code(),
                response.statusCode()
        );
    }

    @Test
    public void maxInitialLineLengthIsHonoured() throws Exception {
        final String longName = repeated('a', 5000);

        server = RestServer.start(buildApi(), 0);
        Assertions.assertEquals(
                HttpResponseStatus.REQUEST_URI_TOO_LONG.code(),
                get("/v1/hello/" + longName).statusCode()
        );
        server.close();

        server = RestServer.of(buildApi()).withMaxInitialLineLength(16 * 1024).start(0);
        final HttpResponse<byte[]> raised = get("/v1/hello/" + longName);
        Assertions.assertEquals(200, raised.statusCode());
        Assertions.assertEquals("\"Hello " + longName + "!\"", text(raised));
    }

    @Test
    public void maxHeaderSizeIsHonoured() throws Exception {
        server = RestServer.start(buildApi(), 0);
        Assertions.assertEquals(
                HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE.code(),
                getWithABigHeader().statusCode()
        );
        server.close();

        server = RestServer.of(buildApi()).withMaxHeaderSize(32 * 1024).start(0);
        final HttpResponse<byte[]> raised = getWithABigHeader();
        Assertions.assertEquals(200, raised.statusCode());
        Assertions.assertEquals("\"Hello world!\"", text(raised));
    }

    private HttpResponse<byte[]> getWithABigHeader() throws Exception {
        return httpClient.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("http://" + HOST + ":" + server.port() + "/v1/hello/world"))
                        .header("X-Big", repeated('a', 10_000))
                        .GET()
                        .build(),
                BodyHandlers.ofByteArray()
        );
    }

    private static String repeated(final char of,
                                   final int times) {
        final StringBuilder result = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            result.append(of);
        }
        return result.toString();
    }

    @Test
    public void ownHandlerSeesTheRequestBeforeTheApi() throws Exception {
        final List<String> seen = new CopyOnWriteArrayList<>();

        server = RestServer.of(buildApi())
                .withHandler(() -> new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx,
                                            final Object msg) {
                        if (msg instanceof HttpRequest) {
                            seen.add(((HttpRequest) msg).uri());
                        }
                        ctx.fireChannelRead(msg);
                    }
                })
                .start(0);

        Assertions.assertEquals(200, get("/v1/hello/world").statusCode());
        Assertions.assertEquals(List.of("/v1/hello/world"), seen);
    }

    @Test
    public void observersReachBothTheFilesAndTheApi() throws Exception {
        writeFile();

        final List<String> completed = new CopyOnWriteArrayList<>();
        final List<String> routed = new CopyOnWriteArrayList<>();
        // a file is completed from the listener of the write which sent it, so it can land just after the
        // client already has the body - the count is what this waits on rather than the assertion
        final CountDownLatch bothCompleted = new CountDownLatch(2);

        final FileSet files = FileSet.builder().serve("/files", filesRoot).build();
        // one factory for both, which is what makes a request observed once wherever it was answered: the
        // file handler is built here, so it is this call which hands it the same one the api gets
        final RestApiObserverFactory observers = () -> new RestApiObserver() {
            private String uri;

            @Override
            public void onRequestReceived(final ChannelHandlerContext ctx,
                                          final HttpRequest request) {
                uri = request.uri();
            }

            @Override
            public void onHandlingStarted(final RestContext context) {
                routed.add(context.pathExpression());
            }

            @Override
            public void onRequestCompleted(final HttpResponseStatus status,
                                           final long bytes,
                                           final long nanos) {
                completed.add(uri);
                bothCompleted.countDown();
            }
        };

        server = RestServer.of(buildApi())
                .withHandler(() -> new FileServerHandler(
                        files, new JsonErrorHandler(), new StdErrChannelErrorHandler(), observers))
                .withObservers(observers)
                .start(0);

        Assertions.assertEquals(200, get("/v1/hello/world").statusCode());
        Assertions.assertEquals(200, get("/files/thing.txt").statusCode());

        Assertions.assertTrue(
                bothCompleted.await(5, TimeUnit.SECONDS),
                "only these requests were observed: " + completed
        );
        Assertions.assertTrue(completed.contains("/v1/hello/world"), "api request not observed");
        Assertions.assertTrue(completed.contains("/files/thing.txt"), "file request not observed");
        Assertions.assertEquals(List.of("/v1/hello/{name}"), routed);
    }

    @Test
    public void startsOnABootstrapOfItsOwn() throws Exception {
        server = RestServer.of(buildApi())
                .start(new NettyServerBuilder().port(0).host(HOST).workerThreads(2));

        Assertions.assertTrue(server.port() > 0);
        Assertions.assertEquals(200, get("/v1/hello/world").statusCode());
    }
}
