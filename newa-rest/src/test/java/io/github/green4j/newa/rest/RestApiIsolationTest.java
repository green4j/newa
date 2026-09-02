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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Every {@link RestApi} keeps its own per-thread matchers. A process which publishes more than one API - a
 * public one and an admin one, say - hands both to the same event loop threads, and each must keep resolving
 * its own paths.
 */
class RestApiIsolationTest {

    private static RestApi apiAnswering(final String path,
                                        final String answer) {
        final RestApiBuilder builder = new RestApiBuilder(
                answer + "-api",
                "isolation tests",
                1,
                "test-build"
        );
        builder.getTxt(path, (context, output) -> output.append(answer));
        return builder.build();
    }

    private static EmbeddedChannel channelOf(final RestApi api) {
        return new EmbeddedChannel(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        (channel, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
    }

    private static void assertAnswers(final EmbeddedChannel channel,
                                      final String path,
                                      final String expected) {
        final FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                path
        );
        channel.writeInbound(request);
        final FullHttpResponse response = channel.readOutbound();
        Assertions.assertNotNull(response);
        try {
            Assertions.assertEquals(200, response.status().code(),
                    "unexpected status for " + path);
            Assertions.assertEquals(expected,
                    response.content().toString(StandardCharsets.UTF_8));
        } finally {
            response.release();
        }
    }

    @Test
    public void testTwoApisOnOneThreadResolveTheirOwnPaths() {
        final EmbeddedChannel first = channelOf(apiAnswering("/first", "first"));
        final EmbeddedChannel second = channelOf(apiAnswering("/second", "second"));
        try {
            assertAnswers(first, "/v1/first", "first");
            assertAnswers(second, "/v1/second", "second");
            // and again, now that both have populated their matchers on this thread
            assertAnswers(first, "/v1/first", "first");
            assertAnswers(second, "/v1/second", "second");
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    public void testOneApiDoesNotResolveAnotherApisPaths() {
        final EmbeddedChannel first = channelOf(apiAnswering("/first", "first"));
        final EmbeddedChannel second = channelOf(apiAnswering("/second", "second"));
        try {
            assertAnswers(first, "/v1/first", "first");

            final FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/v1/first"
            );
            second.writeInbound(request);
            final FullHttpResponse response = second.readOutbound();
            Assertions.assertNotNull(response);
            try {
                Assertions.assertEquals(404, response.status().code());
            } finally {
                response.release();
            }
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }
}
