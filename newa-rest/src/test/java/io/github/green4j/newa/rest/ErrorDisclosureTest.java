/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What an error response says. A failure of the server tells the client its status and no more: the message
 * of a wrapped cause names a type of the implementation and a path of the file system, and the stack trace
 * names the classes the server is built from. Everything else carries a message written by hand, and that is
 * what the client is there to read.
 */
class ErrorDisclosureTest {
    private static final String SECRET = "/etc/secret/db.conf";
    /** The part of it which survives being escaped into JSON, so one assertion serves both renderers. */
    private static final String SECRET_TAIL = "db.conf";
    private static final String A_FRAME = "io.github.green4j.newa.rest.RestApiHandler";

    /**
     * A response, read off the channel and released before anything looks at it.
     */
    private static final class Answer {
        private final int status;
        private final String body;

        private Answer(final int status,
                       final String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Answer answer(final HttpErrorHandler errors,
                                 final HttpMethod method,
                                 final String uri,
                                 final RestHandle handler) {
        final RestApiBuilder builder = new RestApiBuilder(
                "disclosure-test",
                "what an error response says",
                1,
                "test-build"
        );
        builder.get("/thing", handler);

        final EmbeddedChannel channel = new EmbeddedChannel(
                new RestApiHandler(
                        builder.build(),
                        errors,
                        (ch, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri));

            Answer answer = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (answer == null && outbound instanceof FullHttpResponse) {
                    final FullHttpResponse response = (FullHttpResponse) outbound;
                    final ByteBuf content = response.content();
                    answer = new Answer(
                            response.status().code(),
                            content.toString(StandardCharsets.UTF_8)
                    );
                }
                ReferenceCountUtil.release(outbound);
            }
            Assertions.assertNotNull(answer, "nothing was answered");
            return answer;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static Answer failing(final HttpErrorHandler errors) {
        return answer(errors, HttpMethod.GET, "/v1/thing", (context, result) -> {
            throw new IllegalStateException(
                    "Failed to read " + SECRET,
                    new FileNotFoundException(SECRET));
        });
    }

    private static void assertSaysNothingOfTheFailure(final Answer answer) {
        Assertions.assertEquals(500, answer.status);
        Assertions.assertFalse(answer.body.contains("IllegalStateException"), answer.body);
        Assertions.assertFalse(answer.body.contains("FileNotFoundException"), answer.body);
        Assertions.assertFalse(answer.body.contains(SECRET_TAIL), answer.body);
        Assertions.assertFalse(answer.body.contains(A_FRAME), answer.body);
    }

    /**
     * @return both renderers, each named, so that every rule below is asked of both of them.
     */
    private static Stream<Arguments> bothRenderers() {
        return Stream.of(
                Arguments.of("json", (Supplier<HttpErrorHandler>) JsonErrorHandler::new),
                Arguments.of("text", (Supplier<HttpErrorHandler>) TextErrorHandler::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothRenderers")
    public void saysNothingOfAFailure(final String renderer,
                                      final Supplier<HttpErrorHandler> errors) {
        final Answer answer = failing(errors.get());

        Assertions.assertEquals(500, answer.status, renderer);
        Assertions.assertFalse(answer.body.contains("IllegalStateException"), answer.body);
        Assertions.assertFalse(answer.body.contains("FileNotFoundException"), answer.body);
        Assertions.assertFalse(answer.body.contains(SECRET_TAIL), answer.body);
        Assertions.assertFalse(answer.body.contains(A_FRAME), answer.body);
    }

    /**
     * @return both renderers in the form which is asked to say everything, for a server being debugged.
     */
    private static Stream<Arguments> bothRenderersDisclosingInternals() {
        return Stream.of(
                Arguments.of("json", (Supplier<HttpErrorHandler>) JsonErrorHandler::disclosingInternals),
                Arguments.of("text", (Supplier<HttpErrorHandler>) TextErrorHandler::disclosingInternals));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothRenderersDisclosingInternals")
    public void disclosingInternalsSaysAllOfIt(final String renderer,
                                               final Supplier<HttpErrorHandler> errors) {
        final Answer answer = failing(errors.get());

        Assertions.assertEquals(500, answer.status, renderer);
        Assertions.assertTrue(answer.body.contains("IllegalStateException"), answer.body);
        Assertions.assertTrue(answer.body.contains("FileNotFoundException"), answer.body); // the cause chain
        Assertions.assertTrue(answer.body.contains(SECRET_TAIL), answer.body);
        Assertions.assertTrue(answer.body.contains(A_FRAME), answer.body);
    }

    /**
     * Everything which is not a failure of the server carries a message written by hand, and that message
     * is what the client is there to read. Each rule is asked of both renderers.
     *
     * @return one case per rule and renderer: what it is, the renderer, the request to make, the handler
     *         behind it, the status it answers with, and what the body has to name.
     */
    private static Stream<Arguments> everythingWhichIsNotAFailure() {
        final RestHandle ok = (context, result) -> result.ok();
        final RestHandle badRequest = (context, result) -> {
            throw new BadRequestException("Missing parameter: name");
        };
        // a deliberate refusal is not a failure: the message says what the limit was, and whoever asked is
        // meant to read it. This is what admission control answers a chunked response with
        final RestHandle refusal = (context, result) -> {
            throw new HttpException(HttpResponseStatus.SERVICE_UNAVAILABLE, "Server is at its limit");
        };

        return bothRenderers().flatMap(renderer -> {
            final Object name = renderer.get()[0];
            final Object errors = renderer.get()[1];
            return Stream.of(
                    Arguments.of("a path nothing serves is named, " + name, errors,
                            HttpMethod.GET, "/v1/nowhere", ok, 404, "nowhere"),
                    Arguments.of("a method nothing allows is named, " + name, errors,
                            HttpMethod.POST, "/v1/thing", ok, 405, "POST"),
                    Arguments.of("the message of a bad request is rendered, " + name, errors,
                            HttpMethod.GET, "/v1/thing", badRequest, 400, "Missing parameter: name"),
                    Arguments.of("the message of a deliberate refusal is rendered, " + name, errors,
                            HttpMethod.GET, "/v1/thing", refusal, 503, "Server is at its limit"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everythingWhichIsNotAFailure")
    public void theMessageIsRendered(final String what,
                                     final Supplier<HttpErrorHandler> errors,
                                     final HttpMethod method,
                                     final String uri,
                                     final RestHandle handler,
                                     final int status,
                                     final String named) {
        final Answer answer = answer(errors.get(), method, uri, handler);

        Assertions.assertEquals(status, answer.status, what);
        Assertions.assertTrue(answer.body.contains(named), answer.body);
    }

    /**
     * An exception of the user's own, carrying a status of its own, reaches the renderer as it was thrown.
     */
    @Test
    public void testAnExceptionOfTheUsersOwnKeepsItsStatusAndMessage() {
        final Answer answer = answer(new JsonErrorHandler(), HttpMethod.GET, "/v1/thing",
                (context, result) -> {
                    throw new OutOfStockException("A-17");
                });

        Assertions.assertEquals(409, answer.status);
        Assertions.assertTrue(answer.body.contains("Out of stock: A-17"), answer.body);
    }

    static final class OutOfStockException extends HttpException {
        private static final long serialVersionUID = 1L;

        OutOfStockException(final String sku) {
            super(HttpResponseStatus.CONFLICT, "Out of stock: " + sku);
        }
    }
}
