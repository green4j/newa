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

package io.github.green4j.newa.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    private final Received received = new Received();

    private EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(
                new HttpServerCodec(new HttpDecoderConfig()
                        .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                        .setMaxHeaderSize(MAX_HEADER_SIZE)),
                new HttpObjectAggregator(1024, true),
                new DecoderFailureHandler(),
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

    @Test
    public void aRequestLinePastTheLimitIsAnswered414() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /" + repeated('a', MAX_INITIAL_LINE_LENGTH) + " HTTP/1.1\r\nHost: h\r\n\r\n");

        final String answer = answerOf(channel);
        Assertions.assertTrue(answer.startsWith("HTTP/1.1 414 "), answer);
        Assertions.assertTrue(answer.contains("connection: close"), answer);
        // the decoder discards everything it reads from now on, so a connection left open is one nothing
        // would ever be read from again
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertTrue(received.uris.isEmpty(), received.uris.toString());

        channel.finishAndReleaseAll();
    }

    @Test
    public void aHeaderBlockPastTheLimitIsAnswered431() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET / HTTP/1.1\r\nHost: h\r\nX-Big: "
                + repeated('a', MAX_HEADER_SIZE) + "\r\n\r\n");

        final String answer = answerOf(channel);
        Assertions.assertTrue(answer.startsWith("HTTP/1.1 431 "), answer);
        Assertions.assertTrue(answer.contains("connection: close"), answer);
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertTrue(received.uris.isEmpty(), received.uris.toString());

        channel.finishAndReleaseAll();
    }

    @Test
    public void anythingElseTheDecoderRefusesIsAnswered400() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET\r\n\r\n"); // a request line which is not one

        final String answer = answerOf(channel);
        Assertions.assertTrue(answer.startsWith("HTTP/1.1 400 "), answer);
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertTrue(received.uris.isEmpty(), received.uris.toString());

        channel.finishAndReleaseAll();
    }

    @Test
    public void aRequestWithinTheLimitsPassesThrough() {
        final EmbeddedChannel channel = channelWithTheHandler();

        send(channel, "GET /ok HTTP/1.1\r\nHost: h\r\n\r\n");

        Assertions.assertEquals("", answerOf(channel));
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(List.of("/ok"), received.uris);

        channel.finishAndReleaseAll();
    }
}
