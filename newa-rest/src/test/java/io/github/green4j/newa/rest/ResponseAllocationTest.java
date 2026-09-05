/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Response content is copied into a buffer taken from the channel's allocator rather than into a heap buffer
 * the transport would have to copy into a direct one again right before the socket write. That makes the
 * buffer pooled, and a pooled buffer that is never written has to be handed back explicitly: these tests
 * cover both the happy path and the paths where the response is abandoned.
 * <p>
 * The allocator here is unpooled-but-direct on purpose: its metric counts bytes that were allocated and not
 * yet freed, so a leak shows up as a non-zero reading instead of as pool growth.
 */
class ResponseAllocationTest {
    private static final byte[] BYTES = "0123456789".getBytes(StandardCharsets.US_ASCII);

    private final ByteBuffer byteBufferContent = ByteBuffer.wrap(BYTES);

    private final List<Throwable> reported = new ArrayList<>();

    private UnpooledByteBufAllocator allocator;
    private EmbeddedChannel channel;

    private RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "allocation-test",
                "allocation tests",
                1,
                "test-build"
        );
        builder.getJson("/json", (context, output) -> output.stringValue("hello"));
        builder.getTxt("/txt", (context, output) -> output.append("hello"));
        builder.get("/bytes", (context, result) ->
                result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES, 0, BYTES.length));
        builder.get("/byte-buffer", (context, result) ->
                result.ok(byteBufferContent));
        builder.get("/incremental", (context, result) ->
                result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES.length)
                        .append(BYTES, 0, BYTES.length)
                        .done());
        // declares more content than it appends: done() must reject it and free what was allocated
        builder.get("/short-content", (context, result) ->
                result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES.length + 1)
                        .append(BYTES, 0, BYTES.length)
                        .done());
        // handles the rejection itself and answers anyway: the rejected buffer is nobody else's to free
        builder.get("/short-content-recovered", (context, result) -> {
            try {
                result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES.length + 1)
                        .append(BYTES, 0, BYTES.length)
                        .done();
            } catch (final IllegalStateException expected) {
                result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES, 0, BYTES.length);
            }
        });
        // fails once the response buffer is already allocated and partially filled
        builder.get("/fails-midway", (context, result) -> {
            result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES.length)
                    .append(BYTES, 0, BYTES.length);
            throw new IllegalStateException("Boom");
        });
        // fails after the response has already been written and handed over to the pipeline
        builder.get("/fails-after-writing", (context, result) -> {
            result.ok(HttpHeaderValues.TEXT_PLAIN, BYTES, 0, BYTES.length);
            throw new IllegalStateException("Boom");
        });
        return builder.build();
    }

    @BeforeEach
    public void setUp() {
        allocator = new UnpooledByteBufAllocator(true);
        channel = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> reported.add(cause)
                )
        );
        channel.config().setAllocator(allocator);
        Assertions.assertEquals(0, usedMemory());
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
        Assertions.assertTrue(reported.isEmpty(), "unexpected channel error: " + reported);
    }

    private long usedMemory() {
        return allocator.metric().usedDirectMemory()
                + allocator.metric().usedHeapMemory();
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

    private void assertAllocatedFromChannelAllocator(final String path,
                                                     final int expectedStatus,
                                                     final String expectedContent) {
        final FullHttpResponse response = get(path);
        try {
            Assertions.assertEquals(expectedStatus, response.status().code());
            if (expectedContent != null) {
                Assertions.assertEquals(expectedContent,
                        response.content().toString(StandardCharsets.UTF_8));
            }
            Assertions.assertTrue(response.content().isDirect(),
                    "content of " + path + " must come from the channel's direct allocator");
            Assertions.assertTrue(usedMemory() > 0);
        } finally {
            response.release();
        }
        Assertions.assertEquals(0, usedMemory(),
                "writing " + path + " must not leave anything allocated");
    }

    /**
     * Every form a response can take, each of which must be rendered into the channel's own allocator and
     * leave nothing of it behind.
     *
     * @param path    to ask for.
     * @param status  it answers with.
     * @param content it answers, or nothing to look at the body of an error page.
     */
    @ParameterizedTest
    @CsvSource({
        "/v1/json,         200, '\"hello\"'",
        "/v1/txt,          200, hello",
        "/v1/bytes,        200, 0123456789",
        "/v1/byte-buffer,  200, 0123456789",
        "/v1/incremental,  200, 0123456789",
        "/v1/unknown,      404, "                 // an error page is allocated the same way
    })
    public void everyResponseIsAllocatedFromTheChannelAllocator(final String path,
                                                                final int status,
                                                                final String content) {
        assertAllocatedFromChannelAllocator(path, status, content);
    }

    @Test
    public void testByteBufferResponseKeepsCallerPosition() {
        get("/v1/byte-buffer").release();

        Assertions.assertEquals(0, byteBufferContent.position(),
                "the caller's buffer must not be consumed by writing the response");
    }

    @Test
    public void testContentLengthMismatchReleasesResponse() {
        final FullHttpResponse response = get("/v1/short-content");
        try {
            Assertions.assertEquals(500, response.status().code());
            // the mismatch is a failure of the server, so the client is told the status and nothing else
            Assertions.assertFalse(
                    response.content().toString(StandardCharsets.UTF_8)
                            .contains("Expected content length"));
        } finally {
            response.release();
        }
        Assertions.assertEquals(0, usedMemory(),
                "the rejected response must not leak its buffer");
    }

    @Test
    public void testContentLengthMismatchReleasesResponseEvenIfTheHandlerRecovers() {
        final FullHttpResponse response = get("/v1/short-content-recovered");
        try {
            Assertions.assertEquals(200, response.status().code());
            Assertions.assertEquals("0123456789",
                    response.content().toString(StandardCharsets.UTF_8));
        } finally {
            response.release();
        }
        Assertions.assertEquals(0, usedMemory(),
                "the rejected response must not leak its buffer");
    }

    @Test
    public void testHandlerFailingAfterWritingLeavesTheWrittenResponseIntact() {
        final FullHttpResponse written = get("/v1/fails-after-writing");
        try {
            // the first response was handed to the pipeline before the failure, so it must be readable
            Assertions.assertEquals(200, written.status().code());
            Assertions.assertEquals("0123456789",
                    written.content().toString(StandardCharsets.UTF_8));
        } finally {
            written.release();
        }
        // the answer has gone out, so the failure has no response left to be told in: a 500 written after
        // it would be read by the peer as the answer to its next request. See SingleResponseTest
        Assertions.assertNull(channel.readOutbound(), "one request is answered once");
        Assertions.assertEquals(1, reported.size(), "and the failure is still told, to the channel");
        reported.clear();

        Assertions.assertEquals(0, usedMemory());
    }

    @Test
    public void testHandlerFailingMidwayReleasesResponse() {
        final FullHttpResponse response = get("/v1/fails-midway");
        try {
            Assertions.assertEquals(500, response.status().code());
            Assertions.assertFalse(
                    response.content().toString(StandardCharsets.UTF_8).contains("Boom"));
        } finally {
            response.release();
        }
        Assertions.assertEquals(0, usedMemory(),
                "the abandoned response must not leak its buffer");
    }
}
