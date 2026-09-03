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
import java.util.ArrayList;
import java.util.List;

/**
 * Where the cause of an error goes now that the response no longer carries it. Everything which ends as an
 * error response is reported exactly once, to a plain {@link HttpApiObserver} - the file server has nothing
 * else - and with the exception as it was thrown rather than as it was wrapped to be answered.
 */
class ErrorReportingTest {
    private static final String SECRET = "/etc/secret/db.conf";

    /**
     * One observer for every request of a channel, so a test can count what a whole exchange reported.
     */
    private static final class Recorder implements HttpApiObserver, HttpApiObserverFactory {
        private final List<HttpException> notRouted = new ArrayList<>();
        private final List<Throwable> failed = new ArrayList<>();
        private final List<HttpResponseStatus> failedWith = new ArrayList<>();
        private final List<HttpResponseStatus> completed = new ArrayList<>();

        @Override
        public HttpApiObserver newObserver() {
            return this;
        }

        @Override
        public void onRequestNotRouted(final HttpException cause) {
            notRouted.add(cause);
        }

        @Override
        public void onResponseFailed(final HttpResponseStatus status,
                                     final Throwable error) {
            failedWith.add(status);
            failed.add(error);
        }

        @Override
        public void onRequestCompleted(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            completed.add(status);
        }
    }

    @TempDir
    private Path root;

    private Recorder observed;

    @BeforeEach
    public void setUp() throws IOException {
        observed = new Recorder();
        Files.write(root.resolve("small.txt"), "small".getBytes(StandardCharsets.UTF_8));
    }

    private void get(final String uri,
                     final ChannelHandler handler) {
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void getFromApi(final String uri,
                            final RestHandle handler) {
        final RestApiBuilder builder = new RestApiBuilder(
                "reporting-test",
                "where the cause of an error goes",
                1,
                "test-build"
        );
        builder.get("/thing", handler);

        get(uri, new RestApiHandler(
                builder.build(),
                new JsonErrorHandler(),
                (ch, cause) -> {
                    throw new AssertionError(cause);
                },
                ResponseChunks.defaults(),
                observed
        ));
    }

    private void getFromFiles(final String uri,
                              final FileSet files) {
        get(uri, new FileServerHandler(
                files,
                new TextErrorHandler(),
                (ch, cause) -> {
                    throw new AssertionError(cause);
                },
                observed
        ));
    }

    @Test
    public void testAHandlerFailureIsReportedWithItsOriginalCause() {
        final IllegalStateException boom = new IllegalStateException("Failed to read " + SECRET);

        getFromApi("/v1/thing", (context, result) -> {
            throw boom;
        });

        Assertions.assertEquals(1, observed.failed.size(), "reported exactly once");
        Assertions.assertSame(boom, observed.failed.get(0), "as it was thrown, not as it was wrapped");
        Assertions.assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, observed.failedWith.get(0));
        Assertions.assertTrue(observed.notRouted.isEmpty(), "the request did reach an endpoint");
        Assertions.assertEquals(1, observed.completed.size());
    }

    @Test
    public void testARequestWhichReachedNothingIsReportedAsNotRouted() {
        getFromApi("/v1/nowhere", (context, result) -> result.ok());

        Assertions.assertEquals(1, observed.notRouted.size());
        Assertions.assertTrue(observed.notRouted.get(0) instanceof PathNotFoundException);
        Assertions.assertTrue(observed.failed.isEmpty(), "one cause, one event");
        Assertions.assertEquals(1, observed.completed.size());
    }

    @Test
    public void testADeliberateRefusalIsReportedWithTheStatusItAnswers() {
        final HttpException refused = new HttpException(
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                "Server is at its limit");

        getFromApi("/v1/thing", (context, result) -> {
            throw refused;
        });

        Assertions.assertEquals(1, observed.failed.size());
        Assertions.assertSame(refused, observed.failed.get(0));
        Assertions.assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, observed.failedWith.get(0));
    }

    @Test
    public void testTheFileServerReportsAFailureWithItsOriginalCause() {
        final RuntimeException boom = new RuntimeException("Failed to stat " + SECRET);

        getFromFiles("/files/small.txt", FileSet.builder()
                .serve("/files", root, (file, relativePath) -> {
                    throw boom;
                })
                .build());

        Assertions.assertEquals(1, observed.failed.size(), "the file server had nowhere to say this before");
        Assertions.assertSame(boom, observed.failed.get(0));
        Assertions.assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, observed.failedWith.get(0));
        Assertions.assertTrue(observed.notRouted.isEmpty(), "a failure is not a routing outcome");
    }

    @Test
    public void testTheFileServerReportsAMissingFileAsNotRouted() {
        getFromFiles("/files/missing.txt", FileSet.builder().serve("/files", root).build());

        Assertions.assertEquals(1, observed.notRouted.size());
        Assertions.assertTrue(observed.notRouted.get(0) instanceof PathNotFoundException);
        Assertions.assertTrue(observed.failed.isEmpty());
    }

    @Test
    public void testTheFileServerReportsAMethodItDoesNotAllowAsNotRouted() {
        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(
                FileSet.builder().serve("/files", root).build(),
                new TextErrorHandler(),
                (ch, cause) -> {
                    throw new AssertionError(cause);
                },
                observed
        ));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.PUT, "/files/small.txt"));
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            channel.finishAndReleaseAll();
        }

        Assertions.assertEquals(1, observed.notRouted.size());
        Assertions.assertTrue(observed.notRouted.get(0) instanceof MethodNotAllowedException);
        Assertions.assertTrue(observed.failed.isEmpty());
    }
}
