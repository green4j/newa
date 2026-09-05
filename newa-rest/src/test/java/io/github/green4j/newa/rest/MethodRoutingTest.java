/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.NettyServer;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * How HEAD and OPTIONS are routed: OPTIONS like any other method - an endpoint answers where one was
 * registered, and nothing else does - and HEAD with one exception, that a path with no HEAD endpoint of its
 * own is answered by its GET one.
 */
class MethodRoutingTest {
    private static final String HOST = "127.0.0.1";
    private static final String BODY = "a body of a known length";
    private static final String HEAD_BODY = "a body of its own, of another length";
    private static final String PATH = "/v1/thing";
    private static final String ALLOWED = "GET, HEAD, OPTIONS";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static RestApiBuilder builder() {
        final RestApiBuilder builder = new RestApiBuilder(
                "method-routing-test",
                "method routing tests",
                1,
                "test-build"
        );
        builder.getTxt("/thing", (context, output) -> output.append(BODY));
        return builder;
    }

    private static RestApi getOnly() {
        return builder().build();
    }

    private static RestApi withHeadAndOptions() {
        final RestApiBuilder builder = builder();
        // deliberately not what the GET renders: the length is how the test tells which one answered
        builder.headTxt("/thing", (context, output) -> output.append(HEAD_BODY));
        builder.options("/thing", (context, result) -> {
            context.responseHeaders().set(HttpHeaderNames.ALLOW, ALLOWED);
            result.ok();
        });
        return builder.build();
    }

    private static RestApi postOnly() {
        final RestApiBuilder builder = new RestApiBuilder(
                "method-routing-test",
                "method routing tests",
                1,
                "test-build"
        );
        builder.postTxt("/thing", (context, output) -> output.append(BODY));
        return builder.build();
    }

    @Test
    public void aRegisteredHeadAnswersInsteadOfTheGet() throws Exception {
        server = RestServer.start(withHeadAndOptions(), 0);

        final HttpResponse<byte[]> head = head(PATH);

        Assertions.assertEquals(200, head.statusCode());
        // the handler renders the whole body and the codec drops it, so the peer is told the length it
        // would have got without being sent it
        Assertions.assertEquals(0, head.body().length);
        Assertions.assertEquals(String.valueOf(HEAD_BODY.length()), contentLengthOf(head));
    }

    @Test
    public void aHeadWithoutOneOfItsOwnIsAnsweredByTheGet() throws Exception {
        server = RestServer.start(getOnly(), 0);

        final HttpResponse<byte[]> got = send(request(PATH).GET());
        final HttpResponse<byte[]> head = head(PATH);

        Assertions.assertEquals(200, head.statusCode());
        Assertions.assertEquals(0, head.body().length);
        // the GET handler ran and its response was measured, which is the whole point of falling back to it
        Assertions.assertEquals(contentLengthOf(got), contentLengthOf(head));
        Assertions.assertEquals(String.valueOf(BODY.length()), contentLengthOf(head));
    }

    @Test
    public void aHeadOnAPathTheGetDoesNotServeIsNotFound() throws Exception {
        server = RestServer.start(getOnly(), 0);

        Assertions.assertEquals(404, head("/v1/nothing").statusCode());
    }

    @Test
    public void aHeadOnAnApiWhichServesNoGetIsRefused() throws Exception {
        server = RestServer.start(postOnly(), 0);

        Assertions.assertEquals(405, head(PATH).statusCode());
    }

    @Test
    public void aRegisteredOptionsIsRouted() throws Exception {
        server = RestServer.start(withHeadAndOptions(), 0);

        final HttpResponse<byte[]> options = send(request(PATH).method("OPTIONS",
                HttpRequest.BodyPublishers.noBody()));

        Assertions.assertEquals(200, options.statusCode());
        Assertions.assertEquals(ALLOWED, headerOf(options, HttpHeaderNames.ALLOW.toString()));
    }

    @Test
    public void anOptionsWithoutOneRegisteredIsStillRefused() throws Exception {
        server = RestServer.start(getOnly(), 0);

        Assertions.assertEquals(405,
                send(request(PATH).method("OPTIONS", HttpRequest.BodyPublishers.noBody())).statusCode());
    }

    @Test
    public void aMethodTheApiKnowsNothingAboutIsRefused() throws Exception {
        server = RestServer.start(withHeadAndOptions(), 0);

        final String trace = new RawHttp(HOST, server.port()).head("TRACE", PATH);

        Assertions.assertEquals("HTTP/1.1 405 Method Not Allowed", RawHttp.statusOf(trace), trace);
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://" + HOST + ":" + server.port() + path));
    }

    private HttpResponse<byte[]> head(final String path) throws Exception {
        return send(request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<byte[]> send(final HttpRequest.Builder request) throws Exception {
        return httpClient.send(request.build(), BodyHandlers.ofByteArray());
    }

    private static String contentLengthOf(final HttpResponse<byte[]> response) {
        return headerOf(response, HttpHeaderNames.CONTENT_LENGTH.toString());
    }

    private static String headerOf(final HttpResponse<byte[]> response,
                                   final String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
