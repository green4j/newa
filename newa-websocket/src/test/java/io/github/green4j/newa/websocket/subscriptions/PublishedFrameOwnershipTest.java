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

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A publication rendered once for every subscriber: who owns the buffer handed to
 * {@link EntitySubscriptions#publishAndRelease(ByteBuf)}, and that nothing of it is left behind.
 */
class PublishedFrameOwnershipTest {
    private static final int WRITABILITY_FLAG = 1;

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

    @Test
    void shouldGiveEverySubscribedSessionADuplicateOfTheFrameRenderedOnce() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();
        final ClientSession three = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);
        subscriptions.add(three);

        final ByteBuf frame = text("E=1");
        final long sequence = subscriptions.publishAndRelease(frame);

        assertEquals(1, sequence, "a publication is numbered whichever way it is made");
        assertEquals(3, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");

        assertEquals("E=1", readText(one));
        assertEquals("E=1", readText(two));
        assertEquals("E=1", readText(three));

        assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void shouldReleaseAFrameNoSessionIsThereToTake() {
        final ByteBuf frame = text("E=1");

        assertEquals(1, subscriptions.publishAndRelease(frame),
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
        subscriptions.publishAndRelease(frame);

        assertEquals(1, frame.refCnt(),
                "the skipped duplicate is released by the session it was given to");

        assertNull(readText(lagging), "the frame was skipped, not queued");
        assertEquals("E=1", readText(keepingUp), "one slow peer does not stop the fan-out");

        assertEquals(0, frame.refCnt());

        setWritable(channelOf(lagging), true);
    }

    @Test
    void shouldGiveEverySubscribedSessionADuplicateWithoutNumberingTheWalk() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);

        final ByteBuf frame = text("notice");
        final int walked = subscriptions.forEachSessionAndRelease(frame);

        assertEquals(2, walked);
        assertEquals(2, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");
        assertEquals(0, subscriptions.publicationSequence(),
                "an administrative broadcast is not a publication of the entity");

        assertEquals("notice", readText(one));
        assertEquals("notice", readText(two));

        assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void shouldReleaseAWalkedFrameNoSessionIsThereToTake() {
        final ByteBuf frame = text("notice");

        assertEquals(0, subscriptions.forEachSessionAndRelease(frame));
        assertEquals(0, frame.refCnt(), "the buffer is released, not leaked");
    }

    @Test
    void shouldLeaveTheFrameToTheCallerWhenItIsNotHandedOver() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        subscriptions.add(one);
        subscriptions.add(two);

        final ByteBuf frame = text("E=1");
        subscriptions.publish(frame);
        subscriptions.forEachSession(frame);

        assertEquals("E=1", readText(one));
        assertEquals("E=1", readText(one));
        assertEquals("E=1", readText(two));
        assertEquals("E=1", readText(two));

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
        subscriptions.publishAndRelease(frame);

        assertEquals(1, frame.refCnt(), "only the session still subscribed took a duplicate");

        assertNull(readText(gone));
        assertNotNull(readText(staying));

        assertEquals(0, frame.refCnt());
    }
}
