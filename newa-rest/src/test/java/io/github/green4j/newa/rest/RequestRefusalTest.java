/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The requests this server refuses before anything reads them - a body, a request line or a header block
 * past its limit, and a request the decoder could make nothing of. Each is answered in front of every
 * handler, and each is reported all the same.
 * <p>
 * The bracket matters more than the individual event: anything counting requests breaks silently if a kind
 * of request goes missing from it, and these are the kind a server gets more of when it is being probed.
 */
class RequestRefusalTest {
    private static final int MAX_INITIAL_LINE_LENGTH = 64;
    private static final int MAX_HEADER_SIZE = 128;
    private static final int MAX_CONTENT_LENGTH = 32;

    private final List<String> events = new ArrayList<>();

    /** One per request, as a real one is, and it records the whole of what it was told. */
    private final class Observed implements HttpObserver {
        @Override
        public void onRequestReceived(final ChannelHandlerContext ctx,
                                      final HttpRequest request) {
            events.add("received " + request.uri()
                    + (request.decoderResult().isFailure() ? " (substitute)" : ""));
        }

        @Override
        public void onRequestRefused(final HttpResponseStatus status,
                                     final Throwable cause) {
            events.add("refused " + status.code() + " " + cause.getClass().getSimpleName());
        }

        @Override
        public void onRequestNotRouted(final HttpException cause) {
            events.add("not-routed " + cause.status().code());
        }

        @Override
        public void onResponseFailed(final HttpResponseStatus status,
                                     final Throwable error) {
            events.add("failed " + status.code());
        }

        @Override
        public void onRequestCompleted(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            events.add("completed " + status.code());
        }
    }

    private static RestApi api() {
        final RestApiBuilder builder = new RestApiBuilder("Test API", "Test API", 1, "0.0.1");
        builder.get("/hello", (context, result) ->
                result.ok(HttpHeaderValues.APPLICATION_JSON, "\"hi\"".getBytes(StandardCharsets.US_ASCII),
                        0, 4));
        return builder.build();
    }

    private final EmbeddedChannel channel = new EmbeddedChannel(
            RestServer.of(api())
                    .withMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                    .withMaxHeaderSize(MAX_HEADER_SIZE)
                    .withMaxContentLength(MAX_CONTENT_LENGTH)
                    .withObservers(Observed::new)
                    .pipeline()
    );

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private void send(final String request) {
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    private String answer() {
        final StringBuilder head = new StringBuilder();
        for (Object written = channel.readOutbound(); written != null; written = channel.readOutbound()) {
            if (written instanceof ByteBuf) {
                final ByteBuf bytes = (ByteBuf) written;
                try {
                    head.append(bytes.toString(StandardCharsets.US_ASCII));
                } finally {
                    bytes.release();
                }
            }
        }
        return head.toString();
    }

    private static String repeated(final char of,
                                   final int times) {
        final StringBuilder result = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            result.append(of);
        }
        return result.toString();
    }

    /**
     * @return one case per limit: what it is, the request which reaches it, and the status it is answered
     *         with.
     */
    private static Stream<Arguments> whatIsRefusedInFrontOfTheApi() {
        return Stream.of(
                Arguments.of("A body past maxContentLength",
                        "POST /v1/hello HTTP/1.1\r\nHost: h\r\nContent-Length: "
                                + MAX_CONTENT_LENGTH * 2 + "\r\n\r\n",
                        413),
                Arguments.of("A request line past maxInitialLineLength",
                        "GET /v1/" + repeated('a', MAX_INITIAL_LINE_LENGTH)
                                + " HTTP/1.1\r\nHost: h\r\n\r\n",
                        414),
                Arguments.of("A header block past maxHeaderSize",
                        "GET /v1/hello HTTP/1.1\r\nHost: h\r\nX-Big: "
                                + repeated('a', MAX_HEADER_SIZE) + "\r\n\r\n",
                        431),
                Arguments.of("A request line which is not one",
                        "GET\r\n\r\n",
                        400));
    }

    @ParameterizedTest(name = "{0} is answered {2} and reported")
    @MethodSource("whatIsRefusedInFrontOfTheApi")
    public void whatIsRefusedIsStillReported(final String what,
                                             final String request,
                                             final int status) {
        send(request);

        Assertions.assertTrue(answer().startsWith("HTTP/1.1 " + status + " "), what);
        Assertions.assertEquals(1, countOf("refused " + status + " "), events.toString());
        Assertions.assertEquals(1, countOf("completed " + status), events.toString());
    }

    @ParameterizedTest(name = "{0} completes its bracket exactly once")
    @MethodSource("whatIsRefusedInFrontOfTheApi")
    public void andCompletesItsBracketExactlyOnce(final String what,
                                                  final String request,
                                                  final int status) {
        send(request);

        // the same shape every other request has, so nothing counting them has to know this one was refused
        Assertions.assertEquals(1, countOf("received "), events.toString());
        Assertions.assertEquals(1, countOf("completed "), events.toString());
        Assertions.assertEquals("completed " + status, events.get(events.size() - 1), what);
        Assertions.assertEquals(0, countOf("not-routed "),
                "A refused request was reported as one the router turned down");
    }

    @Test
    public void aRequestRefusedByTheDecoderIsHandedOverAsTheSubstituteItIs() {
        // the one thing an observer has to know about a decoder refusal: the uri is the decoder's, not the
        // peer's, and it would be reported as a real request for /bad-request without this
        send("GET /v1/" + repeated('a', MAX_INITIAL_LINE_LENGTH) + " HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals("received /bad-request (substitute)", events.get(0), events.toString());
    }

    @Test
    public void aRequestWithinEveryLimitIsReportedAsItAlwaysWas() {
        send("GET /v1/hello HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals(
                List.of("received /v1/hello", "completed 200"),
                events
        );
    }

    @Test
    public void andOneTheRouterTurnsDownIsStillTheRoutersToReport() {
        // the line between the two: a refusal happened in front of the api, a 404 happened inside it
        send("GET /v1/nowhere HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals(0, countOf("refused "), events.toString());
        Assertions.assertEquals(1, countOf("not-routed 404"), events.toString());
        Assertions.assertEquals(1, countOf("completed 404"), events.toString());
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
}
