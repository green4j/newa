/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The order the stages of one request are reported in, and the one rule that holds across every form the
 * response can take: a request is completed exactly once. Anything counting requests, or writing a line per
 * request, relies on that - and on it not depending on how the response happened to be produced.
 */
class ObserverLifecycleTest {
    /** Ends after two steps, so the whole thing fits in one chunk and the response completes at once. */
    private static final class ShortCursor implements ChunkedJsonRestHandle.Cursor {
        private int written;

        @Override
        public boolean writeNext(final JsonGenerator output) {
            if (written == 0) {
                output.startArray();
            }
            output.stringValue("row-" + written);
            return ++written < 2;
        }

        @Override
        public void close() {
        }
    }

    private final List<String> events = new ArrayList<>();

    /** What the last request was told twice - the close of the handling bracket, then the request's own. */
    private final long[] handled = new long[2];
    private final long[] completed = new long[2];

    /** One per request, so what it records is this request's and nothing else's. */
    private final class Observed implements RestApiObserver {
        private String pathExpression;

        @Override
        public void onRequestReceived(final ChannelHandlerContext ctx,
                                      final HttpRequest request) {
            events.add("received " + request.uri());
        }

        private String name() {
            return pathExpression != null ? pathExpression : "-";
        }

        @Override
        public void onHandlingStarted(final RestContext context) {
            pathExpression = context.pathExpression();
            events.add("handling " + pathExpression);
        }

        @Override
        public void onHandlingFinished(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            events.add("handled " + name() + " " + status.code()
                    + " " + (bytes > 0 ? "with-body" : "empty"));
            handled[0] = bytes;
            handled[1] = durationNanos;
        }

        @Override
        public void onRequestNotRouted(final HttpException cause) {
            events.add("not-routed " + cause.status().code());
        }

        @Override
        public void onResponseFailed(final HttpResponseStatus status,
                                     final Throwable error) {
            events.add("failed " + name() + " " + status.code());
        }

        @Override
        public void onCursorOpened(final int openCursors) {
            events.add("cursor-opened");
        }

        @Override
        public void onChunkWritten(final int bytes) {
            events.add("chunk");
        }

        @Override
        public void onCursorClosed(final int openCursors,
                                   final long bytes,
                                   final long durationNanos,
                                   final Outcome outcome) {
            events.add("cursor-closed " + outcome);
        }

        @Override
        public void onRequestCompleted(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            events.add("completed " + name() + " " + status.code()
                    + " " + (bytes > 0 ? "with-body" : "empty"));
            completed[0] = bytes;
            completed[1] = durationNanos;
        }
    }

    private final RestApiObserverFactory observers = Observed::new;

    private final EmbeddedChannel channel = new EmbeddedChannel(
            new RestApiHandler(
                    buildTestApi(),
                    new JsonErrorHandler(),
                    (ch, cause) -> { },
                    ResponseChunks.defaults(),
                    observers
            )
    );

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "observer-lifecycle-test",
                "observer lifecycle tests",
                1,
                "test-build"
        );
        builder.getJson("/ping", (context, output) -> output.stringValue("pong"));
        builder.getJson("/rows/{count}", (context, output) ->
                output.numberValue(context.pathParameters().valueRequiredAsInt("count"))
        ).withPathParameterDescriptions("count - How many");
        builder.getJson("/boom", (context, output) -> {
            throw new BadRequestException("not today");
        });
        builder.get("/declined", (context, result) ->
                result.error(new HttpException(HttpResponseStatus.CONFLICT, "declined, not thrown")));
        builder.get("/leaks", (context, result) -> {
            throw new HttpException(HttpResponseStatus.CONFLICT, "thrown, not declared");
        });
        builder.get("/chunked", new ChunkedJsonRestHandler(context -> new ShortCursor()));
        return builder.build();
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private void get(final String path) {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                path
        ));
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    private long countOf(final String prefix) {
        long count = 0;
        for (final String event : events) {
            if (event.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Serves the same requests through a handler which observes nothing, and asserts every one of them is
     * answered all the same.
     *
     * @param observers the factory to build the handler with, null for no factory at all.
     */
    private static void serveWithoutObserving(final HttpObserverFactory observers) {
        final EmbeddedChannel unobserved = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> { },
                        ResponseChunks.defaults(),
                        observers
                )
        );

        try {
            for (final String path : List.of("/v1/ping", "/v1/boom", "/v1/chunked", "/v1/nowhere")) {
                unobserved.writeInbound(new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        path
                ));

                int written = 0;
                Object outbound;
                while ((outbound = unobserved.readOutbound()) != null) {
                    written++;
                    ReferenceCountUtil.release(outbound);
                }

                Assertions.assertTrue(written > 0, "nothing was answered for " + path);
            }
        } finally {
            unobserved.finishAndReleaseAll();
        }
    }

    @Test
    public void testAnUnobservedRequestIsServedJustTheSame() {
        serveWithoutObserving(null); // no factory at all

        serveWithoutObserving(() -> null); // a factory is free to observe no request, and the clock is
        // not even read for one

        Assertions.assertEquals(List.of(), events,
                "a handler of its own must report nothing to the observer of this one");
    }

    @Test
    public void testAResponseWrittenInOnePieceReportsTheWholeSequence() {
        get("/v1/ping");

        Assertions.assertEquals(
                List.of("received /v1/ping", "handling /v1/ping", "handled /v1/ping 200 with-body",
                        "completed /v1/ping 200 with-body"),
                events);
    }

    /**
     * The close of the handling bracket carries what the close of the request carries, which is what lets an
     * observer of the handling and nothing else keep nothing between the two - not the status, and not a
     * reading of its own clock.
     */
    @Test
    public void testTheHandlingBracketClosesWithTheArgumentsOfTheRequest() {
        get("/v1/ping");

        Assertions.assertTrue(handled[0] > 0, "the response had a body: " + events);
        Assertions.assertEquals(completed[0], handled[0], "the same bytes");
        Assertions.assertEquals(completed[1], handled[1], "and the same duration, read once for both");
    }

    @Test
    public void testAFailedHandlerReportsTheFailureAndStillCompletes() {
        get("/v1/boom");

        Assertions.assertEquals(
                List.of("received /v1/boom", "handling /v1/boom",
                        "failed /v1/boom 400",
                        "handled /v1/boom 400 with-body", "completed /v1/boom 400 with-body"),
                events);
    }

    /**
     * However a handle ends in an error - throwing it, as {@code /boom} does, or declaring it, as these two
     * do - the failure is reported inside the handling bracket, and the bracket still closes before the
     * request does.
     */
    @Test
    public void testAFailureIsReportedInsideTheHandlingBracket() {
        get("/v1/declined");
        get("/v1/leaks");

        Assertions.assertEquals(
                List.of("received /v1/declined", "handling /v1/declined",
                        "failed /v1/declined 409",
                        "handled /v1/declined 409 with-body", "completed /v1/declined 409 with-body",
                        "received /v1/leaks", "handling /v1/leaks",
                        "failed /v1/leaks 409",
                        "handled /v1/leaks 409 with-body", "completed /v1/leaks 409 with-body"),
                events);
    }

    @Test
    public void testAChunkedResponseCompletesAfterItsCursorIsClosed() {
        get("/v1/chunked");

        Assertions.assertEquals("received /v1/chunked", events.get(0));
        Assertions.assertEquals("handling /v1/chunked", events.get(1));
        Assertions.assertEquals("cursor-opened", events.get(2));
        Assertions.assertTrue(countOf("chunk") > 0, "the body was pulled in chunks");

        final int closed = events.indexOf("cursor-closed COMPLETED");
        Assertions.assertTrue(closed > 0, "the cursor must report itself: " + events);
        Assertions.assertEquals(closed + 2, events.size() - 1,
                "the completion is the last thing reported: " + events);
        Assertions.assertEquals("handled /v1/chunked 200 with-body", events.get(events.size() - 2),
                "the cursor closes inside the handling bracket, which closes inside the request: " + events);
        Assertions.assertEquals("completed /v1/chunked 200 with-body", events.get(events.size() - 1),
                "a chunked response completes like any other: " + events);
    }

    @Test
    public void testTheEndpointExpressionIsReportedRatherThanTheUri() {
        get("/v1/rows/17");
        get("/v1/rows/999999");

        Assertions.assertEquals(
                List.of(
                        "received /v1/rows/17",
                        "handling /v1/rows/{count}",
                        "handled /v1/rows/{count} 200 with-body",
                        "completed /v1/rows/{count} 200 with-body",
                        "received /v1/rows/999999",
                        "handling /v1/rows/{count}",
                        "handled /v1/rows/{count} 200 with-body",
                        "completed /v1/rows/{count} 200 with-body"
                ),
                events,
                "a metric labelled by the URI would grow a series per parameter value");
    }

    @Test
    public void testAnUnsupportedMethodIsNotRoutedEither() {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/v1/ping"
        ));
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }

        Assertions.assertEquals(
                List.of("received /v1/ping", "not-routed 405", "completed - 405 with-body"),
                events);
    }

    @Test
    public void testTheContextRefusesToHandOutWhatDidNotOutliveTheRequest() {
        final List<RestContext> captured = new ArrayList<>();
        final EmbeddedChannel late = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> { },
                        ResponseChunks.defaults(),
                        (RestApiObserverFactory) () -> new RestApiObserver() {
                            @Override
                            public void onHandlingStarted(final RestContext context) {
                                // while the handler still owns the request, all of it is there
                                Assertions.assertNotNull(context.request());
                                Assertions.assertNotNull(context.pathParameters());
                                captured.add(context);
                            }
                        }
                )
        );
        try {
            late.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/v1/rows/17"
            ));
            Object outbound;
            while ((outbound = late.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            late.finishAndReleaseAll();
        }

        // the handler has returned, so the path parameters belong to whatever comes next on this thread
        final RestContext context = captured.get(0);
        Assertions.assertThrows(IllegalStateException.class, context::pathParameters,
                "answering with the next request\'s values is the one failure nothing else would catch");

        // what a late callback is meant to report by is still there
        Assertions.assertEquals("/v1/rows/{count}", context.pathExpression());
        Assertions.assertEquals("/v1/rows/17", context.uri());
        Assertions.assertEquals(HttpMethod.GET, context.method());
    }

    @Test
    public void testEveryRequestIsCompletedExactlyOnce() {
        get("/v1/ping");
        get("/v1/boom");
        get("/v1/chunked");
        get("/v1/nothing-here");

        Assertions.assertEquals(4, countOf("completed"),
                "one terminal event per request, whatever the response was: " + events);
    }

    @Test
    public void testTheReportedSizeIsTheContentAndNotWhatIsLeftOfItAfterEncoding() {
        final List<Long> sizes = new ArrayList<>();
        // with an encoder in front, the content buffer is consumed on the way out - which is exactly when a
        // listener on the write future runs, so the size has to be taken before the write
        final EmbeddedChannel encoding = new EmbeddedChannel(
                new HttpResponseEncoder(),
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> { },
                        ResponseChunks.defaults(),
                        (HttpObserverFactory) () -> new HttpObserver() {
                            @Override
                            public void onRequestCompleted(final HttpResponseStatus status,
                                                           final long bytes,
                                                           final long durationNanos) {
                                sizes.add(bytes);
                            }
                        }
                )
        );
        try {
            encoding.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/v1/ping"
            ));
            Object outbound;
            while ((outbound = encoding.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            encoding.finishAndReleaseAll();
        }

        Assertions.assertEquals(List.of((long) "\"pong\"".length()), sizes);
    }

    @Test
    public void testTheHttpStagesAloneAreEnoughToSeeEveryRequest() {
        final List<String> log = new ArrayList<>();
        final EmbeddedChannel httpOnly = new EmbeddedChannel(
                new RestApiHandler(
                        buildTestApi(),
                        new JsonErrorHandler(),
                        (ch, cause) -> { },
                        ResponseChunks.defaults(),
                        (HttpObserverFactory) () -> new HttpObserver() {
                            private String uri;

                            @Override
                            public void onRequestReceived(final ChannelHandlerContext ctx,
                                                          final HttpRequest request) {
                                uri = request.uri();
                                log.add("-> " + request.method() + " " + uri);
                            }

                            @Override
                            public void onRequestCompleted(final HttpResponseStatus status,
                                                           final long bytes,
                                                           final long durationNanos) {
                                log.add("<- " + uri + " " + status.code());
                            }
                        }
                )
        );
        try {
            for (final String path : List.of("/v1/ping", "/v1/chunked", "/v1/nothing-here")) {
                httpOnly.writeInbound(new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        path
                ));
                Object outbound;
                while ((outbound = httpOnly.readOutbound()) != null) {
                    ReferenceCountUtil.release(outbound);
                }
            }
        } finally {
            httpOnly.finishAndReleaseAll();
        }

        Assertions.assertEquals(
                List.of(
                        "-> GET /v1/ping", "<- /v1/ping 200",
                        "-> GET /v1/chunked", "<- /v1/chunked 200",
                        "-> GET /v1/nothing-here", "<- /v1/nothing-here 404"
                ),
                log,
                "an access log needs nothing from the REST stages");
    }

    @Test
    public void testARequestWhichReachedNoEndpointSkipsTheRestStages() {
        get("/v1/nothing-here");

        Assertions.assertEquals(
                List.of("received /v1/nothing-here", "not-routed 404",
                        "completed - 404 with-body"),
                events,
                "there was no handler, so there was no handler failure");
    }
}
