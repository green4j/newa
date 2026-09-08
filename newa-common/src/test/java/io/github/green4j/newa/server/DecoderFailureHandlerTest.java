/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

class DecoderFailureHandlerTest {
    private static final int MAX_INITIAL_LINE_LENGTH = 64;
    private static final int MAX_HEADER_SIZE = 128;

    /**
     * Stands in for whatever answers: what reaches it is what the handler under test let through.
     */
    private static final class Received extends ChannelInboundHandlerAdapter {
        private final List<String> uris = new ArrayList<>();

        @Override
        public void channelRead(final ChannelHandlerContext ctx,
                               final Object msg) {
            if (msg instanceof FullHttpRequest) {
                uris.add(((FullHttpRequest) msg).uri());
            }
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Stands in for whatever reports: what reaches it is what nothing further in could ever have said,
     * because nothing further in is given the request at all.
     */
    private static final class Refusals implements RefusedRequestObserver {
        private final List<String> statuses = new ArrayList<>();
        private final List<Throwable> causes = new ArrayList<>();
        private final List<String> uris = new ArrayList<>();
        private final List<Boolean> carriedTheFailure = new ArrayList<>();

        @Override
        public void onRequestRefused(final ChannelHandlerContext ctx,
                                     final HttpRequest request,
                                     final HttpResponseStatus status,
                                     final Throwable cause) {
            statuses.add(String.valueOf(status.code()));
            causes.add(cause);
            uris.add(request.uri());
            carriedTheFailure.add(request.decoderResult().isFailure());
        }
    }

    private final Received received = new Received();
    private final Refusals refusals = new Refusals();

    private EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(
                new HttpServerCodec(new HttpDecoderConfig()
                        .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                        .setMaxHeaderSize(MAX_HEADER_SIZE)),
                new HttpObjectAggregator(1024, true),
                new DecoderFailureHandler(refusals),
                received
        );
    }

    private static void send(final EmbeddedChannel channel,
                             final String request) {
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    private static String answerOf(final EmbeddedChannel channel) {
        final ByteBuf answer = channel.readOutbound();
        if (answer == null) {
            return "";
        }
        try {
            return answer.toString(StandardCharsets.US_ASCII);
        } finally {
            answer.release();
        }
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
     * What the decoder refuses, and the status each refusal is answered with.
     *
     * @return one case per refusal: what it is, the request which causes it, the status it is answered
     *         with, and whether the answer announces the close.
     */
    private static Stream<Arguments> whatTheDecoderRefuses() {
        return Stream.of(
                Arguments.of("A request line past the limit",
                        "GET /" + repeated('a', MAX_INITIAL_LINE_LENGTH) + " HTTP/1.1\r\nHost: h\r\n\r\n",
                        "414", true),
                Arguments.of("A header block past the limit",
                        "GET / HTTP/1.1\r\nHost: h\r\nX-Big: "
                                + repeated('a', MAX_HEADER_SIZE) + "\r\n\r\n",
                        "431", true),
                Arguments.of("A request line which is not one", "GET\r\n\r\n", "400", false));
    }

    @ParameterizedTest(name = "{0} is answered {2}")
    @MethodSource("whatTheDecoderRefuses")
    public void whatTheDecoderRefusesIsAnswered(final String what,
                                                final String request,
                                                final String status,
                                                final boolean announcesTheClose) {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, request);

        final String answer = answerOf(channel);
        Assertions.assertTrue(answer.startsWith("HTTP/1.1 " + status + " "), answer);
        if (announcesTheClose) {
            Assertions.assertTrue(answer.contains("connection: close"), answer);
        }
        // the decoder discards everything it reads from now on, so a connection left open is one nothing
        // would ever be read from again
        Assertions.assertFalse(channel.isOpen(), what);
        Assertions.assertTrue(received.uris.isEmpty(), received.uris.toString());
        // the same status, said to this side: what is answered here is answered in front of everything
        // which would otherwise have counted it
        Assertions.assertEquals(List.of(status), refusals.statuses, what);

        channel.finishAndReleaseAll();
    }

    @Test
    public void aRefusalIsReportedWithTheCauseTheDecoderRecorded() {
        // the status alone says a request line was too long; the cause is what a log wants
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /" + repeated('a', MAX_INITIAL_LINE_LENGTH) + " HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals(1, refusals.causes.size());
        Assertions.assertInstanceOf(TooLongHttpLineException.class, refusals.causes.get(0));

        channel.finishAndReleaseAll();
    }

    @Test
    public void whatIsReportedIsTheSubstituteTheDecoderBuilt() {
        // the one thing an observer has to know about these: the uri is not what the peer sent, and the
        // failed decoderResult is what says so
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /" + repeated('a', MAX_INITIAL_LINE_LENGTH) + " HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals(List.of("/bad-request"), refusals.uris);
        Assertions.assertEquals(List.of(true), refusals.carriedTheFailure);

        channel.finishAndReleaseAll();
    }

    @Test
    public void aRequestWithinTheLimitsPassesThrough() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /ok HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals("", answerOf(channel));
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(List.of("/ok"), received.uris);
        Assertions.assertEquals(List.of(), refusals.statuses, "A request which was served was reported "
                + "refused");

        channel.finishAndReleaseAll();
    }
}
