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
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

class CorsTest {
    private static final String HOST = "127.0.0.1";
    private static final String ALLOWED = "https://app.example.com";
    private static final String REFUSED = "https://evil.example";
    private static final String FILE_BODY = "a file, served in front of the api";

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

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

    private static CorsConfig corsAllowing(final String origin) {
        return CorsConfigBuilder.forOrigin(origin)
                .allowedRequestMethods(HttpMethod.GET, HttpMethod.POST)
                .build();
    }

    private Supplier<ChannelHandler> fileHandler() throws IOException {
        Files.write(filesRoot.resolve("thing.txt"), FILE_BODY.getBytes(StandardCharsets.UTF_8));
        final FileSet files = FileSet.builder().serve("/files", filesRoot).build();
        // in front of the api and behind the cors handler, which is where withHandler lands one
        return () -> new FileServerHandler(files);
    }

    private RawHttp client() {
        return new RawHttp(HOST, server.port());
    }

    @Test
    public void anApiResponseCarriesTheHeader() throws Exception {
        server = RestServer.of(buildApi())
                .withCors(corsAllowing(ALLOWED))
                .start(0);

        final String head = client().head("GET", "/v1/hello/world", "Origin: " + ALLOWED);

        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(head), head);
        Assertions.assertEquals(ALLOWED, RawHttp.valueOf(head, ALLOW_ORIGIN), head);
    }

    @Test
    public void andSoDoesAFile() throws Exception {
        server = RestServer.of(buildApi())
                .withCors(corsAllowing(ALLOWED))
                .withHandler(fileHandler())
                .start(0);

        final String head = client().head("GET", "/files/thing.txt", "Origin: " + ALLOWED);

        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(head), head);
        // this is what the placement buys: the file handler writes its head from its own place in the
        // pipeline, so a CorsHandler behind it would decorate the api and skip every file
        Assertions.assertEquals(ALLOWED, RawHttp.valueOf(head, ALLOW_ORIGIN), head);
    }

    @Test
    public void aPreflightIsAnsweredBeforeAnythingElseSeesIt() throws Exception {
        server = RestServer.of(buildApi())
                .withCors(corsAllowing(ALLOWED))
                .withHandler(fileHandler())
                .start(0);

        final String head = client().head(
                "OPTIONS", "/files/thing.txt",
                "Origin: " + ALLOWED,
                "Access-Control-Request-Method: GET");

        // without the cors handler this is the file handler's "405, and GET or HEAD is all I do"
        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(head), head);
        Assertions.assertEquals(ALLOWED, RawHttp.valueOf(head, ALLOW_ORIGIN), head);
        Assertions.assertNotNull(RawHttp.valueOf(head, "Access-Control-Allow-Methods"), head);
    }

    @Test
    public void anUnlistedOriginIsToldNothing() throws Exception {
        server = RestServer.of(buildApi())
                .withCors(corsAllowing(ALLOWED))
                .start(0);

        final String head = client().head("GET", "/v1/hello/world", "Origin: " + REFUSED);

        // the request is answered - it is a request like any other - but the browser is not told it may
        // read what came back, which is what refuses it
        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(head), head);
        Assertions.assertNull(RawHttp.valueOf(head, ALLOW_ORIGIN), head);
    }

    @Test
    public void shortCircuitRefusesItOutright() throws Exception {
        server = RestServer.of(buildApi())
                .withCors(CorsConfigBuilder.forOrigin(ALLOWED).shortCircuit().build())
                .start(0);

        Assertions.assertEquals(
                "HTTP/1.1 403 Forbidden",
                RawHttp.statusOf(client().head("GET", "/v1/hello/world", "Origin: " + REFUSED)));

        Assertions.assertEquals(
                "HTTP/1.1 200 OK",
                RawHttp.statusOf(client().head("GET", "/v1/hello/world", "Origin: " + ALLOWED)));
    }

    @Test
    public void withoutItNothingIsAdded() throws Exception {
        server = RestServer.of(buildApi())
                .withHandler(fileHandler())
                .start(0);

        final String api = client().head("GET", "/v1/hello/world", "Origin: " + ALLOWED);
        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(api), api);
        Assertions.assertNull(RawHttp.valueOf(api, ALLOW_ORIGIN), api);

        final String file = client().head("GET", "/files/thing.txt", "Origin: " + ALLOWED);
        Assertions.assertEquals("HTTP/1.1 200 OK", RawHttp.statusOf(file), file);
        Assertions.assertNull(RawHttp.valueOf(file, ALLOW_ORIGIN), file);

        // and an OPTIONS is still the file handler's to refuse
        final String options = client().head("OPTIONS", "/files/thing.txt");
        Assertions.assertEquals("HTTP/1.1 405 Method Not Allowed", RawHttp.statusOf(options), options);
    }
}
