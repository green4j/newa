/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One request is answered once. Whatever sends the response ends the result, and every terminal call after
 * it is dropped rather than written - a second response would be read by the peer as the answer to its next
 * request. What the dropped call was handed is released, the mistake goes to the
 * {@link io.github.green4j.newa.lang.ChannelErrorHandler}, and nothing is thrown: the response which did go
 * out is the correct one.
 */
class SingleResponseTest {
    private static final AsciiString TEXT = HttpHeaderValues.TEXT_PLAIN;

    /**
     * A response body of one chunk which says whether it was closed - the one thing a dropped chunked
     * response owes: nothing else will ever close it.
     */
    private static final class Body implements ChunkedInput<ByteBuf> {
        private final byte[] content;

        private boolean read;
        private boolean closed;

        private Body(final String content) {
            this.content = content.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public boolean isEndOfInput() {
            return read;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Deprecated
        @Override
        public ByteBuf readChunk(final ChannelHandlerContext ctx) {
            return readChunk(ctx.alloc());
        }

        @Override
        public ByteBuf readChunk(final ByteBufAllocator allocator) {
            if (read) {
                return null;
            }
            read = true;
            return allocator.buffer(content.length).writeBytes(content);
        }

        @Override
        public long length() {
            return -1;
        }

        @Override
        public long progress() {
            return read ? content.length : 0;
        }
    }

    /**
     * One observer for every request of the channel: what a request ends with is what says whether a
     * dropped response was counted twice.
     */
    private static final class Recorder implements HttpObserver, HttpObserverFactory {
        private final List<Throwable> failed = new ArrayList<>();
        private final List<HttpResponseStatus> completed = new ArrayList<>();

        @Override
        public HttpObserver newObserver() {
            return this;
        }

        @Override
        public void onResponseFailed(final HttpResponseStatus status,
                                     final Throwable error) {
            failed.add(error);
        }

        @Override
        public void onRequestCompleted(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            completed.add(status);
        }
    }

    private final List<Throwable> reported = new ArrayList<>();
    private final List<HttpResponseStatus> statuses = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final Recorder observed = new Recorder();

    private UnpooledByteBufAllocator allocator;
    private EmbeddedChannel channel;
    private RestHandle handle;

    @BeforeEach
    public void setUp() {
        final RestApiBuilder builder = new RestApiBuilder(
                "single-response-test",
                "one request, one response",
                1,
                "test-build"
        );
        builder.get("/thing", (context, result) -> handle.handle(context, result));
        builder.get("/second", (context, result) -> result.ok(TEXT, bytes("second"), 0, 6));

        allocator = new UnpooledByteBufAllocator(true);
        channel = new EmbeddedChannel(
                new RestApiHandler(
                        builder.build(),
                        new JsonErrorHandler(),
                        (ch, cause) -> reported.add(cause),
                        ResponseChunks.defaults(),
                        observed
                )
        );
        channel.config().setAllocator(allocator);
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private static byte[] bytes(final String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }

    private long usedMemory() {
        return allocator.metric().usedDirectMemory()
                + allocator.metric().usedHeapMemory();
    }

    /**
     * Sends one request and takes everything the channel wrote back, which is what says how many responses
     * a request produced.
     *
     * @param path to request
     */
    private void get(final String path) {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path));

        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof HttpResponse) {
                    statuses.add(((HttpResponse) outbound).status());
                }
                if (outbound instanceof FullHttpResponse) {
                    bodies.add(((FullHttpResponse) outbound).content()
                            .toString(StandardCharsets.US_ASCII));
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
    }

    @Test
    public void testASecondResponseIsDroppedAndReported() {
        handle = (context, result) -> {
            result.ok(TEXT, bytes("first"), 0, 5);
            result.ok(TEXT, bytes("second"), 0, 6);
        };

        get("/v1/thing");

        Assertions.assertEquals(1, statuses.size(), "the second response must not reach the channel");
        Assertions.assertEquals(Collections.singletonList("first"), bodies);
        Assertions.assertEquals(1, reported.size(), "and the mistake must not be silent");
        Assertions.assertTrue(reported.get(0) instanceof IllegalStateException);
        Assertions.assertEquals(1, observed.completed.size(), "one request, one terminal event");
    }

    @Test
    public void testADroppedResponseReleasesTheBufferItWasHanded() {
        final ByteBuf first = Unpooled.copiedBuffer("first", StandardCharsets.US_ASCII);
        final ByteBuf second = Unpooled.copiedBuffer("second", StandardCharsets.US_ASCII);

        handle = (context, result) -> {
            result.ok(first);
            result.ok(second);
        };

        get("/v1/thing");

        Assertions.assertEquals(Collections.singletonList("first"), bodies);
        Assertions.assertEquals(0, second.refCnt(), "ownership passed to a call which wrote nothing");
        Assertions.assertEquals(1, reported.size());
    }

    @Test
    public void testAFailureAfterTheResponseIsReportedInsteadOfAnswered() {
        final IllegalStateException boom = new IllegalStateException("Boom");

        handle = (context, result) -> {
            result.ok(TEXT, bytes("first"), 0, 5);
            throw boom;
        };

        get("/v1/thing");

        Assertions.assertEquals(1, statuses.size(), "a 500 here would answer the next request");
        Assertions.assertEquals(HttpResponseStatus.OK, statuses.get(0));
        Assertions.assertEquals(Collections.singletonList("first"), bodies);

        Assertions.assertEquals(1, reported.size(), "the failure is told where it can still be told");
        Assertions.assertSame(boom, reported.get(0).getCause());

        Assertions.assertTrue(observed.failed.isEmpty(), "nothing ended as an error response");
        Assertions.assertEquals(1, observed.completed.size());
    }

    @Test
    public void testADroppedChunkedResponseClosesTheBodyItWasHanded() {
        final Body first = new Body("first");
        final Body second = new Body("second");

        handle = (context, result) -> {
            result.ok(TEXT, first);
            result.ok(TEXT, second);
        };

        get("/v1/thing");

        Assertions.assertEquals(1, statuses.size(), "one response head, not two");
        Assertions.assertTrue(second.closed, "nothing else would ever close it");
        Assertions.assertTrue(first.closed, "the one which was written ends as it always did");
        Assertions.assertEquals(1, reported.size());
    }

    @Test
    public void testAnUnsentResponseIsReplacedRatherThanDropped() {
        final ByteBuf second = Unpooled.copiedBuffer("second", StandardCharsets.US_ASCII);

        handle = (context, result) -> {
            result.ok(TEXT, 5).append(bytes("first"), 0, 5); // built, and then left without done()
            result.ok(second);
        };

        get("/v1/thing");

        Assertions.assertEquals(1, statuses.size());
        Assertions.assertEquals(Collections.singletonList("second"), bodies);
        Assertions.assertTrue(reported.isEmpty(),
                "nothing had been sent, so this is a draft abandoned rather than a second answer");
        Assertions.assertEquals(0, usedMemory(), "the abandoned draft must not leak its buffer");
    }

    @Test
    public void testTheNextRequestOfAKeepAliveConnectionGetsItsOwnResponse() {
        handle = (context, result) -> {
            result.ok(TEXT, bytes("first"), 0, 5);
            result.ok(TEXT, bytes("stray"), 0, 5);
        };

        get("/v1/thing");
        get("/v1/second");

        Assertions.assertEquals(2, statuses.size(), "two requests, two responses");
        Assertions.assertEquals(Arrays.asList("first", "second"), bodies,
                "the second request must not be answered with what the first one wrote twice");
    }
}
