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
        server = RestServer.start(0, buildApi());

        final HttpResponse<byte[]> response = get("/v1/hello/world");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("\"Hello world!\"", text(response));
    }

    @Test
    public void everyConnectionGetsItsOwnHandler() throws Exception {
        // RestApiHandler is not @Sharable, so one instance reused across channels would fail on the second
        server = RestServer.start(0, buildApi());

        Assertions.assertEquals(200, get("/v1/hello/one").statusCode());
        Assertions.assertEquals(200, get("/v1/hello/two").statusCode());
        Assertions.assertEquals(200, get("/v1/hello/three").statusCode());
    }

    @Test
    public void compressionIsOffByDefault() throws Exception {
        server = RestServer.start(0, buildApi());

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

        server = RestServer.of(buildApi())
                .withFiles(FileSet.builder().serve("/files", filesRoot).build())
                .withCompression()
                .start(0);

        final HttpResponse<byte[]> file = get("/files/thing.txt");
        Assertions.assertEquals(200, file.statusCode());
        // the compressor sits behind the file handler, so it never sees a file - which is what keeps
        // sendfile(2) available
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

        server = RestServer.of(buildApi())
                .withFiles(FileSet.builder().serve("/files", filesRoot).build())
                .withObservers((RestApiObserverFactory) () -> new RestApiObserver() {
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
                })
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
