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
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code 413} Netty's aggregator answers by itself, said out loud. It is the one refusal which happens
 * behind no handler of this library at all - the aggregator writes the answer from its own place in the
 * pipeline - so without this an oversized upload is a request a server never learns arrived.
 */
class ObservedHttpObjectAggregatorTest {
    private static final int MAX_CONTENT_LENGTH = 32;

    private static final class Refusals implements RefusedRequestObserver {
        private final List<Integer> statuses = new ArrayList<>();
        private final List<String> uris = new ArrayList<>();
        private final List<Throwable> causes = new ArrayList<>();

        @Override
        public void onRequestRefused(final ChannelHandlerContext ctx,
                                     final HttpRequest request,
                                     final HttpResponseStatus status,
                                     final Throwable cause) {
            statuses.add(status.code());
            uris.add(request.uri());
            causes.add(cause);
        }
    }

    /**
     * Stands in for whatever answers: an oversized body must reach nothing.
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

    private final Refusals refusals = new Refusals();
    private final Received received = new Received();

    private EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(
                new HttpServerCodec(),
                new ObservedHttpObjectAggregator(MAX_CONTENT_LENGTH, true, refusals),
                received
        );
    }

    private static void send(final EmbeddedChannel channel,
                             final String request) {
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    private static String answerOf(final EmbeddedChannel channel) {
        final StringBuilder answer = new StringBuilder();
        for (ByteBuf written = channel.readOutbound(); written != null; written = channel.readOutbound()) {
            try {
                answer.append(written.toString(StandardCharsets.US_ASCII));
            } finally {
                written.release();
            }
        }
        return answer.toString();
    }

    private static String repeated(final char of,
                                   final int times) {
        final StringBuilder result = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            result.append(of);
        }
        return result.toString();
    }

    @Test
    public void aBodyDeclaredTooLargeIsAnsweredAndReported() {
        // refused before a byte of the body is read: the declared length is already past the limit
        final EmbeddedChannel channel = channelWithTheHandler();
        final int declared = MAX_CONTENT_LENGTH * 2;

        send(channel, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: " + declared + "\r\n\r\n");

        Assertions.assertTrue(answerOf(channel).startsWith("HTTP/1.1 413 "));
        Assertions.assertEquals(List.of(413), refusals.statuses);
        Assertions.assertEquals(List.of("/upload"), refusals.uris);
        Assertions.assertTrue(received.uris.isEmpty(), "An oversized request reached what answers");

        channel.finishAndReleaseAll();
    }

    @Test
    public void soIsOneWhichGrowsPastTheLimitAsItArrives() {
        // the other way to reach it, and the one a chunked upload takes: nothing was declared to refuse
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "POST /upload HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n");
        send(channel, Integer.toHexString(MAX_CONTENT_LENGTH * 2) + "\r\n"
                + repeated('a', MAX_CONTENT_LENGTH * 2) + "\r\n");

        Assertions.assertEquals(List.of(413), refusals.statuses);
        Assertions.assertEquals(List.of("/upload"), refusals.uris);
        Assertions.assertTrue(received.uris.isEmpty(), "An oversized request reached what answers");

        channel.finishAndReleaseAll();
    }

    @Test
    public void theCauseSaysWhatWasRefused() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 1024\r\n\r\n");

        Assertions.assertEquals(1, refusals.causes.size());
        Assertions.assertTrue(refusals.causes.get(0).getMessage().contains("/upload"),
                refusals.causes.get(0).getMessage());

        channel.finishAndReleaseAll();
    }

    @Test
    public void aBodyWithinTheLimitIsAggregatedAndNotReported() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nbody");

        Assertions.assertEquals(List.of("/upload"), received.uris);
        Assertions.assertEquals(List.of(), refusals.statuses);

        channel.finishAndReleaseAll();
    }
}
