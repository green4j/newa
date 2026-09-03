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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    public void testAPathNothingServes() {
        assertIsThePage(fromApi(HttpMethod.GET, "/v1/nowhere", (context, result) -> result.ok()), 404);
    }

    @Test
    public void testAMethodNothingAllows() {
        assertIsThePage(fromApi(HttpMethod.POST, "/v1/thing", (context, result) -> result.ok()), 405);
    }

    @Test
    public void testAMalformedRequest() {
        assertIsThePage(fromApi(HttpMethod.GET, "/v1/thing", (context, result) -> {
            throw new BadRequestException("Missing parameter: name");
        }), 400);
    }

    @Test
    public void testAFailureOfTheHandler() {
        assertIsThePage(fromApi(HttpMethod.GET, "/v1/thing", (context, result) -> {
            throw new IllegalStateException("Boom");
        }), 500);
    }

    /**
     * An exception of the user's own reaches the same lambda, carrying a status this library never named.
     */
    @Test
    public void testAnExceptionOfTheUsersOwn() {
        assertIsThePage(fromApi(HttpMethod.GET, "/v1/thing", (context, result) -> {
            throw new OutOfStockException("A-17");
        }), 409);
    }

    @Test
    public void testAFileWhichIsNotThere() {
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
