/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteBufWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code getJson} or {@code getTxt} handle which throws after it has written more than the array of the
 * thread's {@link ByteBufWriter} - so part of the response is already in the channel's buffers - gives every
 * one of them back, and the thread's writer with them. An {@link Exception} is answered as an error; an
 * {@link Error}, such as direct memory running out, is not, but leaks nothing either. A chunked response
 * whose last chunk cannot be allocated is not reported completed.
 */
class RenderingFailureTest {
    private static final String LARGE = "x".repeat(1024 * 1024);

    private final List<ByteBuf> taken = new ArrayList<>();
    private final List<Throwable> reported = new ArrayList<>();

    private EmbeddedChannel channel;

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "rendering-failure-test",
                "rendering failure tests",
                1,
                "test-build"
        );
        builder.getJson("/json/exception", (context, output) -> {
            output.startArray();
            output.stringValue(LARGE);
            throw new IllegalStateException("Not today");
        });
        builder.getJson("/json/error", (context, output) -> {
            output.startArray();
            output.stringValue(LARGE);
            throw new OutOfMemoryError("Cannot reserve direct memory");
        });
        builder.getTxt("/txt/exception", (context, output) -> {
            output.append(LARGE);
            throw new IllegalStateException("Not today");
        });
        builder.getTxt("/txt/error", (context, output) -> {
            output.append(LARGE);
            throw new OutOfMemoryError("Cannot reserve direct memory");
        });
        return builder.build();
    }

    @BeforeEach
    public void setUp() {
        channel = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> reported.add(cause)
                )
        );
        channel.config().setAllocator(tracking(taken));
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @CsvSource({
        "/v1/json/exception, 500",
        "/v1/json/error,       0",
        "/v1/txt/exception,  500",
        "/v1/txt/error,        0",
    })
    public void aHandleWhichThrowsGivesBackEveryBufferItTook(final String path,
                                                            final int status) {
        final ByteBufWriter before = writerOfTheThread();

        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path));
        } catch (final Throwable ignored) {
            // an Error goes on up the pipeline, as it always has
        }
        final Object response = channel.readOutbound();
        try {
            if (status == 0) {
                Assertions.assertNull(response, "An Error was answered");
                Assertions.assertEquals(1, reported.size());
                Assertions.assertInstanceOf(OutOfMemoryError.class, reported.get(0));
            } else {
                Assertions.assertEquals(status, ((FullHttpResponse) response).status().code());
            }
        } finally {
            ReferenceCountUtil.release(response);
        }

        Assertions.assertTrue(taken.size() > 2, "The response did not go past the array of the writer");
        for (int i = 0; i < taken.size(); i++) {
            Assertions.assertEquals(0, taken.get(i).refCnt(), "Buffer " + i + " of " + taken.size() + " leaked");
        }
        Assertions.assertSame(before, writerOfTheThread(), "The writer of the thread was not given back");
    }

    @Test
    public void aLastChunkWhichCannotBeAllocatedIsNotACompletedResponse() {
        final int lastChunk = 777; // the whole document, in one step: a size nothing else here asks for
        final RestApiBuilder builder = new RestApiBuilder("last-chunk-test", "last chunk test", 1, "test-build");
        builder.get("/chunked", new ChunkedJsonRestHandler(context -> new ChunkedJsonRestHandle.Cursor() {
            @Override
            public boolean writeNext(final JsonGenerator output) {
                output.stringValue("y".repeat(lastChunk - 2));
                return false;
            }

            @Override
            public void close() {
            }
        }));
        final List<RestApiObserver.Outcome> outcomes = new ArrayList<>();
        final RestApiObserverFactory observers = () -> new RestApiObserver() {
            @Override
            public void onCursorClosed(final int openCursors,
                                       final long bytes,
                                       final long durationNanos,
                                       final Outcome outcome) {
                outcomes.add(outcome);
            }
        };
        final EmbeddedChannel chunked = new EmbeddedChannel(
                new RestApiHandler(
                        builder.build(),
                        new JsonErrorHandler(),
                        (ch, cause) -> reported.add(cause),
                        ResponseChunks.defaults(),
                        observers
                )
        );
        chunked.config().setAllocator(failing(taken, lastChunk));
        try {
            try {
                chunked.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/chunked"));
            } catch (final Throwable ignored) {
                // however the failed write is reported, the outcome is what is asserted
            }
            Object outbound;
            while ((outbound = chunked.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            chunked.finishAndReleaseAll();
        }

        Assertions.assertEquals(List.of(RestApiObserver.Outcome.ABANDONED), outcomes);
        for (int i = 0; i < taken.size(); i++) {
            Assertions.assertEquals(0, taken.get(i).refCnt(), "Buffer " + i + " of " + taken.size() + " leaked");
        }
    }

    private static ByteBufWriter writerOfTheThread() {
        final ByteBufWriter writer = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        writer.abort();
        return writer;
    }

    /**
     * @param taken where every buffer handed out is recorded
     * @return the unpooled allocator, recording every buffer it hands out
     */
    private static ByteBufAllocator tracking(final List<ByteBuf> taken) {
        return failing(taken, -1);
    }

    /**
     * @param taken where every buffer handed out is recorded
     * @param failingCapacity a buffer asked for with this capacity fails to be allocated, as direct memory runs
     *                        out; -1 for none
     * @return the unpooled allocator, recording every buffer it hands out
     */
    private static ByteBufAllocator failing(final List<ByteBuf> taken, final int failingCapacity) {
        final ByteBufAllocator delegate = UnpooledByteBufAllocator.DEFAULT;
        return (ByteBufAllocator) Proxy.newProxyInstance(ByteBufAllocator.class.getClassLoader(),
                new Class<?>[] {ByteBufAllocator.class}, (proxy, method, args) -> {
                    if (args != null && args.length > 0 && Integer.valueOf(failingCapacity).equals(args[0])) {
                        throw new OutOfMemoryError("Cannot reserve direct memory");
                    }
                    final Object result = method.invoke(delegate, args);
                    if (result instanceof ByteBuf buffer) {
                        taken.add(buffer);
                    }
                    return result;
                });
    }
}
