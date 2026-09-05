/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Who owns the buffer of a frame handed to a session. A frame which never reaches the channel is released
 * by the session, and a fan-out gives every session a frame of its own - one buffer written to several
 * channels would be released by the first of them.
 * <p>
 * A broadcast owns its buffer the same way whichever kind of frame it goes out as, so both are asked.
 */
class FrameOwnershipTest {
    private static final int WRITABILITY_FLAG = 1;

    /** The two kinds a broadcast can go out as, which own the buffer they are given identically. */
    private enum Kind {
        TEXT {
            @Override
            void broadcastAndRelease(final WsApi api, final ByteBuf frame) {
                api.broadcastTextAndRelease(frame);
            }

            @Override
            void broadcast(final WsApi api, final ByteBuf frame) {
                api.broadcastText(frame);
            }

            @Override
            Class<? extends WebSocketFrame> frameType() {
                return TextWebSocketFrame.class;
            }
        },
        BINARY {
            @Override
            void broadcastAndRelease(final WsApi api, final ByteBuf frame) {
                api.broadcastBinaryAndRelease(frame);
            }

            @Override
            void broadcast(final WsApi api, final ByteBuf frame) {
                api.broadcastBinary(frame);
            }

            @Override
            Class<? extends WebSocketFrame> frameType() {
                return BinaryWebSocketFrame.class;
            }
        };

        abstract void broadcastAndRelease(WsApi api, ByteBuf frame);

        abstract void broadcast(WsApi api, ByteBuf frame);

        abstract Class<? extends WebSocketFrame> frameType();
    }

    /**
     * @param channel to take one frame off.
     * @param kind    it must have gone out as.
     * @return what it carries, or null if nothing was written.
     */
    private static String readOut(final EmbeddedChannel channel,
                                  final Kind kind) {
        final Object written = channel.readOutbound();
        if (written == null) {
            return null;
        }
        Assertions.assertInstanceOf(kind.frameType(), written, "not a " + kind + " frame");
        final WebSocketFrame frame = (WebSocketFrame) written;
        try {
            return frame.content().toString(StandardCharsets.UTF_8);
        } finally {
            frame.release();
        }
    }

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private static ByteBuf text(final String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private static void setWritable(final EmbeddedChannel channel,
                                    final boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(WRITABILITY_FLAG, writable);
        Assertions.assertEquals(writable, channel.isWritable());
    }

    private ClientSession newSession(final WsApi api) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        return api.newSession(
                new ClientSessionContext(
                        api,
                        null,
                        null,
                        channel,
                        0 // no pinger
                )
        );
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void shouldReleaseAFrameWhichWasSkipped() {
        final WsApi api = new WsApiBuilder(1)
                .withSkipOnBackPressure()
                .build();

        final ClientSession session = newSession(api);
        final EmbeddedChannel channel = channels.get(0);

        setWritable(channel, false);

        final ByteBuf frame = text("abc");
        session.sendText(frame);

        Assertions.assertEquals(0, frame.refCnt(), "the skipped frame is released, not leaked");
        Assertions.assertFalse(session.isClosed(), "skipping keeps the session");

        setWritable(channel, true);
    }

    @Test
    void shouldReleaseAFrameWrittenToAChannelWhichIsGone() {
        final WsApi api = new WsApiBuilder(1).build();

        final ClientSession session = newSession(api);
        channels.get(0).close();

        final ByteBuf frame = text("abc");
        session.sendText(frame);

        Assertions.assertEquals(0, frame.refCnt(), "a frame which could not be written is released");
        Assertions.assertTrue(session.isClosed());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldGiveEveryBroadcastSessionAFrameOfItsOwn(final Kind kind) {
        final WsApi api = new WsApiBuilder(1).build();

        newSession(api);
        newSession(api);
        newSession(api);

        final ByteBuf frame = text("hello");
        kind.broadcastAndRelease(api, frame);

        Assertions.assertEquals(3, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");

        for (int i = 0; i < channels.size(); i++) {
            Assertions.assertEquals("hello", readOut(channels.get(i), kind),
                    "every session must have got the frame");
        }

        Assertions.assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void shouldReleaseABroadcastNoSessionIsThereToTake() {
        final WsApi api = new WsApiBuilder(1).build();

        final ByteBuf frame = text("hello");
        api.broadcastTextAndRelease(frame);

        Assertions.assertEquals(0, frame.refCnt());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldLeaveABroadcastFrameToTheCallerWhenItIsNotHandedOver(final Kind kind) {
        final WsApi api = new WsApiBuilder(1).build();

        newSession(api);
        newSession(api);

        final ByteBuf frame = text("hello");
        kind.broadcast(api, frame);
        kind.broadcast(api, frame); // the same buffer again, which handing it over would have made
        // impossible

        for (int i = 0; i < channels.size(); i++) {
            for (int j = 0; j < 2; j++) {
                Assertions.assertEquals("hello", readOut(channels.get(i), kind));
            }
        }

        Assertions.assertEquals(1, frame.refCnt(), "the buffer is still the caller's");
        frame.release();
    }

    @Test
    void shouldPingWithAPingFrame() {
        final WsApi api = new WsApiBuilder(1).build();

        final ClientSession session = newSession(api);
        session.ping("are you there");

        final Object written = channels.get(0).readOutbound();
        Assertions.assertInstanceOf(PingWebSocketFrame.class, written,
                "a ping must not go out as a text frame");
        Assertions.assertEquals("are you there",
                ((PingWebSocketFrame) written).content().toString(StandardCharsets.UTF_8));
        ((PingWebSocketFrame) written).release();
    }
}
