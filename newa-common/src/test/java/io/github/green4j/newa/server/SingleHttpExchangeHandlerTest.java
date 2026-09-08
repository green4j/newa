/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The handler by itself. What the servers which put it in their pipelines make of it is theirs to pin -
 * {@code PipeliningTest} in {@code newa-rest} and in {@code newa-websocket} are those.
 */
class SingleHttpExchangeHandlerTest {
    @Test
    void aPipelinedRequestIsHeldUntilTheFirstResponseIsWritten() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest first = request();
        final FullHttpRequest pipelined = request();

        Assertions.assertTrue(channel.writeInbound(first));
        Assertions.assertSame(first, channel.readInbound());

        // it was decoded from the same read, so it arrives however hard reads are held back
        Assertions.assertFalse(channel.writeInbound(pipelined));
        Assertions.assertNull(channel.readInbound());
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(1, pipelined.refCnt());

        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.OK)));

        Assertions.assertSame(pipelined, channel.readInbound());
        Assertions.assertTrue(channel.isOpen());

        first.release();
        pipelined.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aThirdRequestIsMoreThanTheConnectionWillHold() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest first = request();
        final FullHttpRequest held = request();
        final FullHttpRequest refused = request();

        Assertions.assertTrue(channel.writeInbound(first));
        Assertions.assertSame(first, channel.readInbound());
        Assertions.assertFalse(channel.writeInbound(held));
        Assertions.assertFalse(channel.writeInbound(refused));

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(0, refused.refCnt());
        Assertions.assertEquals(0, held.refCnt()); // the one this handler owned goes with the connection

        first.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aHeldRequestIsReleasedWithTheConnection() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest first = request();
        final FullHttpRequest held = request();

        Assertions.assertTrue(channel.writeInbound(first));
        Assertions.assertSame(first, channel.readInbound());
        Assertions.assertFalse(channel.writeInbound(held));

        channel.close();

        Assertions.assertEquals(0, held.refCnt());
        first.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void theNextRequestIsReadAfterTheFinalResponseWasWritten() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest first = request();
        final FullHttpRequest next = request();

        Assertions.assertTrue(channel.writeInbound(first));
        channel.readInbound();
        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.OK)));
        Assertions.assertTrue(channel.config().isAutoRead());
        Assertions.assertTrue(channel.writeInbound(next));
        Assertions.assertSame(next, channel.readInbound());

        first.release();
        next.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aManuallyReadChannelStaysManuallyRead() {
        final ReadCounter reads = new ReadCounter();
        final EmbeddedChannel channel = new EmbeddedChannel(
                reads,
                new SingleHttpExchangeHandler()
        );
        channel.config().setAutoRead(false);
        final FullHttpRequest request = request();

        Assertions.assertTrue(channel.writeInbound(request));
        channel.readInbound();
        final int readsBeforeRequest = reads.count;
        channel.read();
        Assertions.assertEquals(readsBeforeRequest, reads.count);
        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.OK)));

        Assertions.assertFalse(channel.config().isAutoRead());
        Assertions.assertEquals(readsBeforeRequest + 1, reads.count);
        request.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aHeldRequestIsNotReplayedByAnInformationalResponse() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest first = request();
        final FullHttpRequest held = request();

        Assertions.assertTrue(channel.writeInbound(first));
        channel.readInbound();
        Assertions.assertFalse(channel.writeInbound(held));
        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.CONTINUE)));

        // the exchange is still the first one's: nothing was replayed and the slot is still taken
        Assertions.assertNull(channel.readInbound());
        Assertions.assertEquals(1, held.refCnt());

        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.OK)));
        Assertions.assertSame(held, channel.readInbound());

        first.release();
        held.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aHandshakeRetiresTheGate() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest handshake = request();

        Assertions.assertTrue(channel.writeInbound(handshake));
        channel.readInbound();
        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.SWITCHING_PROTOCOLS)));

        // what follows a handshake is frames, and answering one is nobody's exchange
        Assertions.assertNull(channel.pipeline().get(SingleHttpExchangeHandler.class));
        Assertions.assertTrue(channel.isOpen());

        handshake.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aRequestHeldBehindAHandshakeGoesWithTheConnection() {
        final EmbeddedChannel channel = new EmbeddedChannel(new SingleHttpExchangeHandler());
        final FullHttpRequest handshake = request();
        final FullHttpRequest held = request();

        Assertions.assertTrue(channel.writeInbound(handshake));
        channel.readInbound();
        Assertions.assertFalse(channel.writeInbound(held));
        Assertions.assertTrue(channel.writeOutbound(response(HttpResponseStatus.SWITCHING_PROTOCOLS)));

        // the encoder which would have written its answer went with the upgrade, so nothing can answer it
        Assertions.assertNull(channel.pipeline().get(SingleHttpExchangeHandler.class));
        Assertions.assertEquals(0, held.refCnt());
        Assertions.assertFalse(channel.isOpen());

        handshake.release();
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest request() {
        return new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/"
        );
    }

    private static DefaultFullHttpResponse response(final HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    }

    private static final class ReadCounter extends ChannelOutboundHandlerAdapter {
        private int count;

        @Override
        public void read(final ChannelHandlerContext ctx) {
            count++;
            ctx.read();
        }
    }
}
