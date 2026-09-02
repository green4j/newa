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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a publication which is already under way may reach. The subscribers are walked through a snapshot,
 * so a session which subscribes while the fan-out runs gets its own snapshot of the state instead of an
 * update it has no state to apply to - even though the subscribers are now held in a list which is changed
 * in place rather than copied.
 */
class PublicationSnapshotTest {
    private static final class ValueSubscriptions extends EntitySubscriptions {
        private int value;

        ValueSubscriptions(final String entityId) {
            super(entityId);
        }

        void publishValue(final int newValue,
                          final Runnable whileFanningOut) {
            value = newValue;
            publish(session -> {
                session.send("U:" + newValue);
                whileFanningOut.run();
            });
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

    private static List<String> framesOf(final ClientSession session) {
        final EmbeddedChannel channel = (EmbeddedChannel) session.channel();

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

    private TestSessions sessions;
    private TestChannel channel;

    @BeforeEach
    void setUp() {
        sessions = new TestSessions();
        channel = new TestChannel();
    }

    @AfterEach
    void tearDown() {
        channel.close();
        sessions.closeAll();
    }

    @Test
    void shouldNotDeliverAPublicationToASessionWhichSubscribedWhileItWasRunning() {
        final ClientSession first = sessions.newSession();
        final ClientSession late = sessions.newSession();

        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");
        channel.subscribe(first, "AA");
        framesOf(first);

        entity.publishValue(1, () -> {
            if (!entity.contains(late)) {
                channel.subscribe(late, "AA"); // joins in the middle of the fan-out
            }
        });

        assertEquals(List.of("U:1"), framesOf(first));
        assertEquals(List.of("S:1"), framesOf(late),
                "a session which joined mid-publication gets the snapshot, never the update alone");

        entity.publishValue(2, () -> { });

        assertEquals(List.of("U:2"), framesOf(first));
        assertEquals(List.of("U:2"), framesOf(late));
    }

    @Test
    void shouldDeliverToEverySessionOnceWhateverTheHolesInTheList() {
        final List<ClientSession> live = new ArrayList<>();
        final List<ClientSession> gone = new ArrayList<>();

        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        for (int i = 0; i < 20; i++) { // every other one leaves, so the list is full of holes
            final ClientSession session = sessions.newSession();
            channel.subscribe(session, "AA");
            if ((i & 1) == 0) {
                channel.unsubscribe(session, "AA");
                gone.add(session);
            } else {
                live.add(session);
            }
        }

        assertEquals(10, entity.numberOfSubscribedSessions());

        for (int i = 0; i < live.size(); i++) {
            framesOf(live.get(i));
        }
        for (int i = 0; i < gone.size(); i++) {
            framesOf(gone.get(i));
        }

        entity.publishValue(3, () -> { });

        for (int i = 0; i < live.size(); i++) {
            assertEquals(List.of("U:3"), framesOf(live.get(i)), "delivered exactly once");
        }
        for (int i = 0; i < gone.size(); i++) {
            assertEquals(List.of(), framesOf(gone.get(i)), "an unsubscribed session gets nothing");
        }
    }
}
