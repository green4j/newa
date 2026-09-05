/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A publication rendered once for every subscriber: who owns the buffer handed to
 * {@link EntitySubscriptions#publishTextAndRelease(ByteBuf)}, and that nothing of it is left behind.
 * <p>
 * A publication owns its buffer the same way whichever kind of frame it goes out as, so both are asked.
 */
class PublishedFrameOwnershipTest {
    private static final int WRITABILITY_FLAG = 1;

    /** The two kinds a publication can go out as, which own the buffer they are given identically. */
    private enum Kind {
        TEXT {
            @Override
            long publishAndRelease(final EntitySubscriptions to, final ByteBuf frame) {
                return to.publishTextAndRelease(frame);
            }

            @Override
            long publish(final EntitySubscriptions to, final ByteBuf frame) {
                return to.publishText(frame);
            }

            @Override
            int forEachSessionAndRelease(final EntitySubscriptions to, final ByteBuf frame) {
                return to.forEachSessionTextAndRelease(frame);
            }

            @Override
            int forEachSession(final EntitySubscriptions to, final ByteBuf frame) {
                return to.forEachSessionText(frame);
            }

            @Override
            Class<? extends WebSocketFrame> frameType() {
                return TextWebSocketFrame.class;
            }
        },
        BINARY {
            @Override
            long publishAndRelease(final EntitySubscriptions to, final ByteBuf frame) {
                return to.publishBinaryAndRelease(frame);
            }

            @Override
            long publish(final EntitySubscriptions to, final ByteBuf frame) {
                return to.publishBinary(frame);
            }

            @Override
            int forEachSessionAndRelease(final EntitySubscriptions to, final ByteBuf frame) {
                return to.forEachSessionBinaryAndRelease(frame);
            }

            @Override
            int forEachSession(final EntitySubscriptions to, final ByteBuf frame) {
                return to.forEachSessionBinary(frame);
            }

            @Override
            Class<? extends WebSocketFrame> frameType() {
                return BinaryWebSocketFrame.class;
            }
        };

        abstract long publishAndRelease(EntitySubscriptions to, ByteBuf frame);

        abstract long publish(EntitySubscriptions to, ByteBuf frame);

        abstract int forEachSessionAndRelease(EntitySubscriptions to, ByteBuf frame);

        abstract int forEachSession(EntitySubscriptions to, ByteBuf frame);

        abstract Class<? extends WebSocketFrame> frameType();
    }

    /**
     * @param session to take one frame off.
     * @param kind    it must have gone out as.
     * @return what it carries, or null if nothing was written.
     */
    private static String read(final ClientSession session,
                               final Kind kind) {
        final Object written = channelOf(session).readOutbound();
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

    private TestSessions sessions;
    private EntitySubscriptions subscriptions;

    private static ByteBuf text(final String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private static EmbeddedChannel channelOf(final ClientSession session) {
        return (EmbeddedChannel) session.channel();
    }

    private static void setWritable(final EmbeddedChannel channel,
                                    final boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(WRITABILITY_FLAG, writable);
    }

    private static String readText(final ClientSession session) {
        final TextWebSocketFrame written = channelOf(session).readOutbound();
        if (written == null) {
            return null;
        }
        try {
            return written.text();
        } finally {
            written.release();
        }
    }

    @BeforeEach
    void setUp() {
        sessions = new TestSessions();
        subscriptions = new EntitySubscriptions("E");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldGiveEverySubscribedSessionADuplicateOfTheFrameRenderedOnce(final Kind kind) {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();
        final ClientSession three = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);
        subscriptions.add(three);

        final ByteBuf frame = text("E=1");
        final long sequence = kind.publishAndRelease(subscriptions, frame);

        assertEquals(1, sequence, "a publication is numbered whichever way it is made");
        assertEquals(3, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");

        assertEquals("E=1", read(one, kind));
        assertEquals("E=1", read(two, kind));
        assertEquals("E=1", read(three, kind));

        assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void shouldReleaseAFrameNoSessionIsThereToTake() {
        final ByteBuf frame = text("E=1");

        assertEquals(1, subscriptions.publishTextAndRelease(frame),
                "the state changed, so the publication is numbered even with nobody to send it to");
        assertEquals(0, frame.refCnt(), "the buffer is released, not leaked");
    }

    @Test
    void shouldNotLeakTheDuplicateOfASessionWhichCanNotKeepUp() {
        final ClientSession lagging = sessions.newSession();
        final ClientSession keepingUp = sessions.newSession();

        subscriptions.add(lagging);
        subscriptions.add(keepingUp);

        setWritable(channelOf(lagging), false);

        final ByteBuf frame = text("E=1");
        subscriptions.publishTextAndRelease(frame);

        assertEquals(1, frame.refCnt(),
                "the skipped duplicate is released by the session it was given to");

        assertNull(readText(lagging), "the frame was skipped, not queued");
        assertEquals("E=1", readText(keepingUp), "one slow peer does not stop the fan-out");

        assertEquals(0, frame.refCnt());

        setWritable(channelOf(lagging), true);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldGiveEverySubscribedSessionADuplicateWithoutNumberingTheWalk(final Kind kind) {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);

        final ByteBuf frame = text("notice");
        final int walked = kind.forEachSessionAndRelease(subscriptions, frame);

        assertEquals(2, walked);
        assertEquals(2, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");
        assertEquals(0, subscriptions.publicationSequence(),
                "an administrative broadcast is not a publication of the entity");

        assertEquals("notice", read(one, kind));
        assertEquals("notice", read(two, kind));

        assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void shouldReleaseAWalkedFrameNoSessionIsThereToTake() {
        final ByteBuf frame = text("notice");

        assertEquals(0, subscriptions.forEachSessionTextAndRelease(frame));
        assertEquals(0, frame.refCnt(), "the buffer is released, not leaked");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void shouldLeaveTheFrameToTheCallerWhenItIsNotHandedOver(final Kind kind) {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);

        final ByteBuf frame = text("E=1");
        kind.publish(subscriptions, frame);
        kind.forEachSession(subscriptions, frame);

        assertEquals("E=1", read(one, kind));
        assertEquals("E=1", read(one, kind));
        assertEquals("E=1", read(two, kind));
        assertEquals("E=1", read(two, kind));

        assertEquals(1, frame.refCnt(), "the same buffer, sent twice, is still the caller's");

        frame.release();
        assertEquals(0, frame.refCnt());
    }

    @Test
    void shouldSkipTheHoleLeftByAnUnsubscribedSession() {
        final ClientSession gone = sessions.newSession();
        final ClientSession staying = sessions.newSession();

        subscriptions.add(gone);
        subscriptions.add(staying);
        subscriptions.remove(gone);

        final ByteBuf frame = text("E=2");
        subscriptions.publishTextAndRelease(frame);

        assertEquals(1, frame.refCnt(), "only the session still subscribed took a duplicate");

        assertNull(readText(gone));
        assertNotNull(readText(staying));

        assertEquals(0, frame.refCnt());
    }
}
