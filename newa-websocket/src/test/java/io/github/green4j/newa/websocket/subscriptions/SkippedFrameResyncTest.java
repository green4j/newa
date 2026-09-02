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
import io.github.green4j.newa.websocket.ClientSessionContext;
import io.github.green4j.newa.websocket.WsApi;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A session which had a frame skipped is re-synchronized by the writability event of its channel. When the
 * channel drains while the frame is being given up on, that event has already come and gone - and without
 * something to make up for it the skipped frame stays a hole in the stream of that session.
 */
class SkippedFrameResyncTest {
    private static final int WRITABILITY_FLAG = 1;

    private static final class ValueSubscriptions extends EntitySubscriptions {
        private int value;

        ValueSubscriptions(final String entityId) {
            super(entityId);
        }

        void publishValue(final int newValue) {
            value = newValue;
            publish(session -> session.send("U:" + newValue));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            session.send("S:" + value);
        }
    }

    private static final class TestChannel extends Channel<ValueSubscriptions> {
        @Override
        protected ValueSubscriptions newEntitySubscriptions(final String entityId) {
            return new ValueSubscriptions(entityId);
        }
    }

    private final EmbeddedChannel channel = new EmbeddedChannel();

    private final WsApi api = new SubscriptionWsApiBuilder(1)
            .withSkipOnBackPressure()
            .build();

    private final ClientSession session = api.newSession(
            new ClientSessionContext(
                    api,
                    null,
                    channel,
                    0 // no pinger
            )
    );

    private final TestChannel entities = new TestChannel();

    private void setWritable(final boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(WRITABILITY_FLAG, writable);
        assertEquals(writable, channel.isWritable());
    }

    private List<String> framesOf() {
        final List<String> result = new ArrayList<>();
        Object written;
        while ((written = channel.readOutbound()) != null) {
            if (written instanceof TextWebSocketFrame) {
                result.add(((TextWebSocketFrame) written).text());
            }
            ReferenceCountUtil.release(written);
        }
        return result;
    }

    @AfterEach
    void tearDown() {
        entities.close();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldResynchronizeASessionWhoseChannelDrainedWhileTheFrameWasSkipped() {
        final ValueSubscriptions entity = entities.getOrCreateEntitySubscriptions("AA");
        entities.subscribe(session, "AA");
        assertEquals(List.of("S:0"), framesOf(), "the first snapshot");

        setWritable(false);
        entity.publishValue(1); // skipped, and the session is marked as lagging
        assertEquals(List.of(), framesOf());

        // The channel drains here, and the writability event it fires reaches no handler which would
        // re-synchronize the session - the race this stands for is a publisher which gets to the point of
        // giving its frame up only after the channel is writable again.
        setWritable(true);

        api.onWriteBackPressure(session);
        channel.runPendingTasks();

        assertEquals(List.of("S:1"), framesOf(),
                "the snapshot the skipped frame is made up for must be re-sent");
        assertFalse(session.isClosed());
    }

    @Test
    void shouldNotResynchronizeASessionWhichSkippedNothing() {
        entities.getOrCreateEntitySubscriptions("AA");
        entities.subscribe(session, "AA");
        assertEquals(List.of("S:0"), framesOf());

        api.onWriteBackPressure(session); // nothing was skipped, so there is nothing to make up for
        channel.runPendingTasks();

        assertEquals(List.of(), framesOf());
    }
}
