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

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

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

    @Test
    public void testJsonSaysNothingOfAFailure() {
        assertSaysNothingOfTheFailure(failing(new JsonErrorHandler()));
    }

    @Test
    public void testTextSaysNothingOfAFailure() {
        assertSaysNothingOfTheFailure(failing(new TextErrorHandler()));
    }

    @Test
    public void testJsonDisclosingInternalsSaysAllOfIt() {
        final Answer answer = failing(JsonErrorHandler.disclosingInternals());

        Assertions.assertEquals(500, answer.status);
        Assertions.assertTrue(answer.body.contains("IllegalStateException"), answer.body);
        Assertions.assertTrue(answer.body.contains("FileNotFoundException"), answer.body); // the cause chain
        Assertions.assertTrue(answer.body.contains(SECRET_TAIL), answer.body);
        Assertions.assertTrue(answer.body.contains(A_FRAME), answer.body);
    }

    @Test
    public void testTextDisclosingInternalsSaysAllOfIt() {
        final Answer answer = failing(TextErrorHandler.disclosingInternals());

        Assertions.assertEquals(500, answer.status);
        Assertions.assertTrue(answer.body.contains("IllegalStateException"), answer.body);
        Assertions.assertTrue(answer.body.contains("FileNotFoundException"), answer.body);
        Assertions.assertTrue(answer.body.contains(SECRET_TAIL), answer.body);
        Assertions.assertTrue(answer.body.contains(A_FRAME), answer.body);
    }

    @Test
    public void testAPathNothingServesIsNamed() {
        for (final HttpErrorHandler errors : new HttpErrorHandler[]{
                new JsonErrorHandler(), new TextErrorHandler()}) {
            final Answer answer = answer(errors, HttpMethod.GET, "/v1/nowhere",
                    (context, result) -> result.ok());

            Assertions.assertEquals(404, answer.status);
            Assertions.assertTrue(answer.body.contains("nowhere"), answer.body);
        }
    }

    @Test
    public void testAMethodNothingAllowsIsNamed() {
        for (final HttpErrorHandler errors : new HttpErrorHandler[]{
                new JsonErrorHandler(), new TextErrorHandler()}) {
            final Answer answer = answer(errors, HttpMethod.POST, "/v1/thing",
                    (context, result) -> result.ok());

            Assertions.assertEquals(405, answer.status);
            Assertions.assertTrue(answer.body.contains("POST"), answer.body);
        }
    }

    @Test
    public void testTheMessageOfABadRequestIsRendered() {
        for (final HttpErrorHandler errors : new HttpErrorHandler[]{
                new JsonErrorHandler(), new TextErrorHandler()}) {
            final Answer answer = answer(errors, HttpMethod.GET, "/v1/thing",
                    (context, result) -> {
                        throw new BadRequestException("Missing parameter: name");
                    });

            Assertions.assertEquals(400, answer.status);
            Assertions.assertTrue(answer.body.contains("Missing parameter: name"), answer.body);
        }
    }

    /**
     * A deliberate refusal is not a failure: the message says what the limit was, and whoever asked is meant
     * to read it. This is what admission control answers a chunked response with.
     */
    @Test
    public void testTheMessageOfADeliberateRefusalIsRendered() {
        for (final HttpErrorHandler errors : new HttpErrorHandler[]{
                new JsonErrorHandler(), new TextErrorHandler()}) {
            final Answer answer = answer(errors, HttpMethod.GET, "/v1/thing",
                    (context, result) -> {
                        throw new HttpException(
                                HttpResponseStatus.SERVICE_UNAVAILABLE,
                                "Server is at its limit");
                    });

            Assertions.assertEquals(503, answer.status);
            Assertions.assertTrue(answer.body.contains("Server is at its limit"), answer.body);
        }
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
