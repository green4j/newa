/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.server.ConnectionObserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.TooLongHttpContentException;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A handshake refused by one of the limits in front of this server. There is no session to report it to, so
 * it goes where this server already reports what it turns down before a session exists: the
 * {@link ChannelErrorHandler}.
 */
class RefusedHandshakeTest {
    private static final int MAX_INITIAL_LINE_LENGTH = 64;
    private static final int MAX_HEADER_SIZE = 128;
    private static final int MAX_CONTENT_LENGTH = 32;
    private static final int DEADLINE_MS = 1000;

    private final List<Throwable> errors = new ArrayList<>();
    private final List<String> closures = new ArrayList<>();

    private final ChannelErrorHandler recorded = (channel, cause) -> errors.add(cause);

    private final ConnectionObserver closings = new ConnectionObserver() {
        @Override
        public void onRequestDeadlineExpired(final Channel channel) {
            closures.add("request deadline");
        }
    };

    private static WsApi echoApi() {
        return new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(Receivers.echo())
                .build();
    }

    private EmbeddedChannel server() {
        return new EmbeddedChannel(
                WsServer.of(echoApi())
                        .withMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                        .withMaxHeaderSize(MAX_HEADER_SIZE)
                        .withMaxContentLength(MAX_CONTENT_LENGTH)
                        .withRequestDeadlineMs(DEADLINE_MS)
                        .withChannelErrorHandler(recorded)
                        .withConnectionObserver(closings)
                        .pipeline()
        );
    }

    private static void send(final EmbeddedChannel channel,
                             final String request) {
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    private static String answerOf(final EmbeddedChannel channel) {
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
     * @return one case per limit: what it is, the handshake which reaches it, the status it is answered
     *         with, and the cause reported for it.
     */
    private static Stream<Arguments> whatIsRefusedBeforeAHandshake() {
        return Stream.of(
                Arguments.of("A body past maxContentLength",
                        "POST /ws/v1 HTTP/1.1\r\nHost: h\r\nContent-Length: "
                                + MAX_CONTENT_LENGTH * 2 + "\r\n\r\n",
                        413, TooLongHttpContentException.class),
                Arguments.of("A request line past maxInitialLineLength",
                        "GET /ws/" + repeated('a', MAX_INITIAL_LINE_LENGTH)
                                + " HTTP/1.1\r\nHost: h\r\n\r\n",
                        414, TooLongHttpLineException.class),
                Arguments.of("A header block past maxHeaderSize",
                        "GET /ws/v1 HTTP/1.1\r\nHost: h\r\nX-Big: "
                                + repeated('a', MAX_HEADER_SIZE) + "\r\n\r\n",
                        431, TooLongHttpHeaderException.class));
    }

    @ParameterizedTest(name = "{0} is answered {2} and reported")
    @MethodSource("whatIsRefusedBeforeAHandshake")
    public void whatIsRefusedIsReported(final String what,
                                        final String handshake,
                                        final int status,
                                        final Class<? extends Throwable> cause) {
        final EmbeddedChannel channel = server();

        send(channel, handshake);

        Assertions.assertTrue(answerOf(channel).startsWith("HTTP/1.1 " + status + " "), what);
        Assertions.assertEquals(1, errors.size(), errors.toString());
        Assertions.assertInstanceOf(cause, errors.get(0), what);

        channel.finishAndReleaseAll();
    }

    @Test
    public void aHandshakeWithinEveryLimitIsUpgradedAndReportedForNothing() {
        final EmbeddedChannel channel = server();

        send(channel, "GET /ws/v1 HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");

        Assertions.assertTrue(answerOf(channel).startsWith("HTTP/1.1 101 "));
        Assertions.assertEquals(List.of(), errors);

        channel.finishAndReleaseAll();
    }

    @Test
    public void aHandshakeWhichNeverArrivesIsReportedToTheConnectionObserver() {
        // the deadline this server has always had, and the close it has always made without a word: before
        // the handshake there is no session, so onSessionClosed is not owed and never comes
        final EmbeddedChannel channel = server();

        send(channel, "GET /ws"); // begun, and left unfinished

        channel.advanceTimeBy(DEADLINE_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(List.of("request deadline"), closures);

        channel.finishAndReleaseAll();
    }
}
