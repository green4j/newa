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

import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * The JSON generators and line builders the handlers render into are thread-local and reused between
 * requests: that is what keeps responses allocation-free. These tests pin what that reuse means through the
 * real pipeline - the same instance serves every response, it starts out big enough for ordinary ones, and it
 * is kept at whatever size the current load needs. When that size stops being needed is a matter of elapsed
 * time, so it is {@link RetainedBufferTest} which covers it, on a clock it can move.
 */
class ResponseBufferRetentionTest {
    private static final int LARGE_CONTENT_SIZE = 4 * ResponseBuffers.DEFAULT_BASE_SIZE;

    /** Reaches the same thread-local generator the JSON handlers use. */
    private static final class JsonGeneratorProbe extends AbstractApplicationJsonHandler {
        ByteArrayJsonGenerator current() {
            return jsonGenerator();
        }
    }

    /** Reaches the same thread-local line builder the plain-text handlers use. */
    private static final class LineBuilderProbe extends AbstractTextPlainHandler {
        ByteArrayLineBuilder current() {
            return lineBuilder();
        }
    }

    private final JsonGeneratorProbe jsonProbe = new JsonGeneratorProbe();
    private final LineBuilderProbe txtProbe = new LineBuilderProbe();

    private EmbeddedChannel channel;

    private static String contentOf(final int size) {
        final StringBuilder result = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            result.append((char) ('a' + i % 26));
        }
        return result.toString();
    }

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "buffer-retention-test",
                "buffer retention tests",
                1,
                "test-build"
        );
        builder.getJson("/json/small", (context, output) -> output.stringValue("small"));
        builder.getJson("/json/large", (context, output) ->
                output.stringValue(contentOf(LARGE_CONTENT_SIZE)));
        builder.getTxt("/txt/small", (context, output) -> output.append("small"));
        builder.getTxt("/txt/large", (context, output) ->
                output.append(contentOf(LARGE_CONTENT_SIZE)));
        return builder.build();
    }

    @BeforeEach
    public void setUp() {
        channel = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private FullHttpResponse get(final String path) {
        final FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                path
        );
        channel.writeInbound(request);
        final FullHttpResponse response = channel.readOutbound();
        Assertions.assertNotNull(response);
        return response;
    }

    @Test
    public void testSmallJsonResponseKeepsThreadLocalGenerator() {
        final ByteArrayJsonGenerator before = jsonProbe.current();

        final FullHttpResponse response = get("/v1/json/small");
        try {
            Assertions.assertEquals(200, response.status().code());
            Assertions.assertEquals("\"small\"",
                    response.content().toString(StandardCharsets.UTF_8));
        } finally {
            response.release();
        }

        Assertions.assertSame(before, jsonProbe.current(),
                "a small response must keep reusing the thread-local generator");
    }

    @Test
    public void testLargeJsonResponseKeepsItsGeneratorWhileLargeResponsesKeepComing() {
        final FullHttpResponse first = get("/v1/json/large");
        try {
            Assertions.assertEquals(200, first.status().code());
            // the quoted string, in full
            Assertions.assertEquals(LARGE_CONTENT_SIZE + 2, first.content().readableBytes());
        } finally {
            first.release();
        }

        final ByteArrayJsonGenerator grown = jsonProbe.current();
        Assertions.assertTrue(grown.capacity() > ResponseBuffers.baseSize());

        // while the load needs this size the buffer must never be taken below it: re-growing megabytes per
        // request would cost far more than holding them. Trimming the slack the doubling left over is fine
        for (int i = 0; i < 200; i++) {
            get("/v1/json/large").release();
            Assertions.assertSame(grown, jsonProbe.current(),
                    "the generator must be reused, iteration " + i);
            Assertions.assertTrue(grown.capacity() >= LARGE_CONTENT_SIZE,
                    "the buffer was taken below what the load needs, iteration " + i);
        }
    }

    @Test
    public void testSmallJsonResponsesNeverShrinkTheGeneratorBelowTheBaseSize() {
        // whatever an earlier test on this thread left behind, small responses may only ever take it down
        // to the base size, and never below
        final ByteArrayJsonGenerator generator = jsonProbe.current();

        for (int i = 0; i < 500; i++) {
            get("/v1/json/small").release();
        }

        Assertions.assertSame(generator, jsonProbe.current(),
                "the generator must never be replaced");
        Assertions.assertTrue(generator.capacity() >= ResponseBuffers.baseSize(),
                "the buffer dropped to " + generator.capacity() + " bytes");
    }

    @Test
    public void testSmallTxtResponseKeepsThreadLocalLineBuilder() {
        final ByteArrayLineBuilder before = txtProbe.current();

        final FullHttpResponse response = get("/v1/txt/small");
        try {
            Assertions.assertEquals(200, response.status().code());
            Assertions.assertEquals("small",
                    response.content().toString(StandardCharsets.UTF_8));
        } finally {
            response.release();
        }

        Assertions.assertSame(before, txtProbe.current(),
                "a small response must keep reusing the thread-local line builder");
    }

    @Test
    public void testLargeTxtResponseKeepsItsLineBuilderWhileLargeResponsesKeepComing() {
        final FullHttpResponse first = get("/v1/txt/large");
        try {
            Assertions.assertEquals(200, first.status().code());
            Assertions.assertEquals(LARGE_CONTENT_SIZE, first.content().readableBytes());
        } finally {
            first.release();
        }

        final ByteArrayLineBuilder grown = txtProbe.current();
        Assertions.assertTrue(grown.capacity() > ResponseBuffers.baseSize());

        for (int i = 0; i < 200; i++) {
            get("/v1/txt/large").release();
            Assertions.assertSame(grown, txtProbe.current(),
                    "the line builder must be reused, iteration " + i);
            Assertions.assertTrue(grown.capacity() >= LARGE_CONTENT_SIZE,
                    "the buffer was taken below what the load needs, iteration " + i);
        }
    }

    @Test
    public void testSmallTxtResponsesNeverShrinkTheLineBuilderBelowTheBaseSize() {
        final ByteArrayLineBuilder lineBuilder = txtProbe.current();

        for (int i = 0; i < 500; i++) {
            get("/v1/txt/small").release();
        }

        Assertions.assertSame(lineBuilder, txtProbe.current(),
                "the line builder must never be replaced");
        Assertions.assertTrue(lineBuilder.capacity() >= ResponseBuffers.baseSize(),
                "the buffer dropped to " + lineBuilder.capacity() + " bytes");
    }
}
