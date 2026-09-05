/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A page of one's own for every error there is. {@link HttpErrorHandler} is one method, so a whole set of them is
 * one lambda, and the same one serves the API and the files in front of it.
 */
class CustomErrorHandlerTest {
    /**
     * Rendered once, when the server is assembled: an error page which is the same every time costs nothing
     * per request.
     *
     * @param status the page is for
     * @return the page
     */
    private static byte[] page(final HttpResponseStatus status) {
        return ("<html><body><h1>" + status.code() + "</h1></body></html>")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static final HttpErrorHandler PAGES = error -> new DefaultFullHttpResponseContent(
            HttpHeaderValues.TEXT_HTML, page(error.status()), 0, page(error.status()).length);

    private static final class Answer {
        private final int status;
        private final String contentType;
        private final String body;

        private Answer(final FullHttpResponse response) {
            this.status = response.status().code();
            this.contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
            this.body = response.content().toString(StandardCharsets.UTF_8);
        }
    }

    @TempDir
    private Path root;

    @BeforeEach
    public void setUp() throws IOException {
        Files.write(root.resolve("small.txt"), "small".getBytes(StandardCharsets.UTF_8));
    }

    private static Answer answer(final ChannelHandler handler,
                                 final HttpMethod method,
                                 final String uri) {
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri));

            Answer answer = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (answer == null && outbound instanceof FullHttpResponse) {
                    answer = new Answer((FullHttpResponse) outbound);
                }
                ReferenceCountUtil.release(outbound);
            }
            Assertions.assertNotNull(answer, "nothing was answered");
            return answer;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static Answer fromApi(final HttpMethod method,
                                  final String uri,
                                  final RestHandle handler) {
        final RestApiBuilder builder = new RestApiBuilder(
                "pages-test",
                "an error page of one's own",
                1,
                "test-build"
        );
        builder.get("/thing", handler);

        return answer(new RestApiHandler(
                builder.build(),
                PAGES,
                (ch, cause) -> {
                    throw new AssertionError(cause);
                }
        ), method, uri);
    }

    private void assertIsThePage(final Answer answer,
                                 final int status) {
        Assertions.assertEquals(status, answer.status);
        Assertions.assertEquals(HttpHeaderValues.TEXT_HTML.toString(), answer.contentType);
        Assertions.assertEquals(new String(page(HttpResponseStatus.valueOf(status)),
                StandardCharsets.US_ASCII), answer.body);
    }

    /**
     * Every error the api can answer with, each of which must reach the one lambda and come back as its
     * page rather than as whatever this library would have said.
     *
     * @return one case per error: what it is, the request to make, the handler behind it, and the status.
     */
    private static Stream<Arguments> everyErrorTheApiAnswers() {
        return Stream.of(
                Arguments.of("a path nothing serves", HttpMethod.GET, "/v1/nowhere",
                        (RestHandle) (context, result) -> result.ok(), 404),
                Arguments.of("a method nothing allows", HttpMethod.POST, "/v1/thing",
                        (RestHandle) (context, result) -> result.ok(), 405),
                Arguments.of("a malformed request", HttpMethod.GET, "/v1/thing",
                        (RestHandle) (context, result) -> {
                            throw new BadRequestException("Missing parameter: name");
                        }, 400),
                Arguments.of("a failure of the handler", HttpMethod.GET, "/v1/thing",
                        (RestHandle) (context, result) -> {
                            throw new IllegalStateException("Boom");
                        }, 500),
                // an exception of the user's own reaches the same lambda, carrying a status this library
                // never named
                Arguments.of("an exception of the user's own", HttpMethod.GET, "/v1/thing",
                        (RestHandle) (context, result) -> {
                            throw new OutOfStockException("A-17");
                        }, 409));
    }

    @ParameterizedTest(name = "{0} is answered with the page for {4}")
    @MethodSource("everyErrorTheApiAnswers")
    public void everyErrorIsAnsweredWithThePageForIt(final String what,
                                                     final HttpMethod method,
                                                     final String uri,
                                                     final RestHandle handler,
                                                     final int status) {
        assertIsThePage(fromApi(method, uri, handler), status);
    }

    @Test
    public void testAFileWhichIsNotThere() {
        // the same lambda in front of the files, which answer their own errors
        assertIsThePage(answer(new FileServerHandler(
                FileSet.builder().serve("/files", root).build(),
                PAGES,
                (ch, cause) -> {
                    throw new AssertionError(cause);
                },
                null
        ), HttpMethod.GET, "/files/missing.txt"), 404);
    }

    static final class OutOfStockException extends HttpException {
        private static final long serialVersionUID = 1L;

        OutOfStockException(final String sku) {
            super(HttpResponseStatus.CONFLICT, "Out of stock: " + sku);
        }
    }
}
