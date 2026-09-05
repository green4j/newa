/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;


/**
 * The JSON generators and line builders the handlers render into are thread-local and reused between
 * requests: that is what keeps responses allocation-free. These tests pin what that reuse means through the
 * real pipeline - the same instance serves every response, it starts out big enough for ordinary ones, and it
 * is kept at whatever size the current load needs. When that size stops being needed is a matter of elapsed
 * time, so it is {@link RetainedBufferTest} which covers it, on a clock it can move.
 */
class ResponseBufferRetentionTest {

    /**
     * The two thread-local buffers a handler renders into. They are held and reused by the same rule, so
     * every case below is asked of both - what differs is only the endpoint and, for JSON, the two quotes
     * a string is rendered inside.
     */
    private enum Kind {
        JSON("/v1/json/small", "/v1/json/large", 2) {
            @Override
            Object buffer(final ResponseBufferRetentionTest test) {
                return test.jsonProbe.current();
            }

            @Override
            int capacity(final ResponseBufferRetentionTest test) {
                return test.jsonProbe.current().capacity();
            }
        },
        TXT("/v1/txt/small", "/v1/txt/large", 0) {
            @Override
            Object buffer(final ResponseBufferRetentionTest test) {
                return test.txtProbe.current();
            }

            @Override
            int capacity(final ResponseBufferRetentionTest test) {
                return test.txtProbe.current().capacity();
            }
        };

        private final String small;
        private final String large;
        /** How much the rendering adds to the content itself: the quotes around a JSON string. */
        private final int overhead;

        Kind(final String small,
                final String large,
                final int overhead) {
            this.small = small;
            this.large = large;
            this.overhead = overhead;
        }

        abstract Object buffer(ResponseBufferRetentionTest test);

        abstract int capacity(ResponseBufferRetentionTest test);
    }

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

    @ParameterizedTest
    @EnumSource(Kind.class)
    public void aSmallResponseKeepsTheThreadLocalBuffer(final Kind kind) {
        final Object before = kind.buffer(this);

        final FullHttpResponse response = get(kind.small);
        try {
            Assertions.assertEquals(200, response.status().code());
        } finally {
            response.release();
        }

        Assertions.assertSame(before, kind.buffer(this),
                "a small response must keep reusing the thread-local buffer");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    public void aLargeResponseKeepsItsBufferWhileLargeResponsesKeepComing(final Kind kind) {
        final FullHttpResponse first = get(kind.large);
        try {
            Assertions.assertEquals(200, first.status().code());
            Assertions.assertEquals(LARGE_CONTENT_SIZE + kind.overhead, first.content().readableBytes());
        } finally {
            first.release();
        }

        final Object grown = kind.buffer(this);
        Assertions.assertTrue(kind.capacity(this) > ResponseBuffers.baseSize());

        // while the load needs this size the buffer must never be taken below it: re-growing megabytes per
        // request would cost far more than holding them. Trimming the slack the doubling left over is fine
        for (int i = 0; i < 200; i++) {
            get(kind.large).release();
            Assertions.assertSame(grown, kind.buffer(this), "the buffer must be reused, iteration " + i);
            Assertions.assertTrue(kind.capacity(this) >= LARGE_CONTENT_SIZE,
                    "the buffer was taken below what the load needs, iteration " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    public void smallResponsesNeverShrinkTheBufferBelowTheBaseSize(final Kind kind) {
        // whatever an earlier test on this thread left behind, small responses may only ever take it down
        // to the base size, and never below
        final Object buffer = kind.buffer(this);

        for (int i = 0; i < 500; i++) {
            get(kind.small).release();
        }

        Assertions.assertSame(buffer, kind.buffer(this), "the buffer must never be replaced");
        Assertions.assertTrue(kind.capacity(this) >= ResponseBuffers.baseSize(),
                "the buffer dropped to " + kind.capacity(this) + " bytes");
    }
}
