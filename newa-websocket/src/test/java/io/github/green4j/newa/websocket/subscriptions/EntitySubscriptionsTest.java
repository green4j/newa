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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySubscriptionsTest {
    private static final class Recording extends EntitySubscriptions {
        private final List<ClientSession> subscribed = new ArrayList<>();
        private final List<ClientSession> unsubscribed = new ArrayList<>();
        private final List<ClientSession> repeated = new ArrayList<>();
        private final List<Long> subscribedAt = new ArrayList<>();
        private final AtomicInteger closedCount = new AtomicInteger();

        Recording() {
            super("E");
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            subscribed.add(session);
            subscribedAt.add(publicationSequence);
        }

        @Override
        protected void onClientSessionRepeatedSubscriptionTry(final ClientSession session) {
            repeated.add(session);
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            unsubscribed.add(session);
        }

        @Override
        protected void onClosed() {
            closedCount.incrementAndGet();
        }
    }

    private TestSessions sessions;
    private Recording subscriptions;

    @BeforeEach
    void setUp() {
        sessions = new TestSessions();
        subscriptions = new Recording();
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @Test
    void shouldPublishToEverySessionWhenOneOfThemThrows() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();
        final ClientSession three = sessions.newSession();

        assertTrue(subscriptions.add(one));
        assertTrue(subscriptions.add(two));
        assertTrue(subscriptions.add(three));

        final List<ClientSession> reached = new ArrayList<>();

        final long sequence = subscriptions.publish(session -> {
            reached.add(session);
            if (session == two) {
                throw new IllegalStateException("the consumer of the application went wrong");
            }
        });

        assertEquals(1, sequence);
        assertEquals(List.of(one, two, three), reached);

        // the sequence number is already spent and the sessions ahead of it have the update, so a
        // publication abandoned half way is not a state anything could recover from
        assertEquals(List.of(two), sessions.writeErrors(),
                "the session whose delivery threw is reported the way a failed write is, and an api "
                        + "closes it from there");
    }

    @Test
    void shouldUnsubscribeEverySessionWhenClosingWhileOneOfThemThrows() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        final List<ClientSession> unsubscribed = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger();

        final EntitySubscriptions entity = new EntitySubscriptions("E") {
            @Override
            protected void onClientSessionUnsubscribed(final ClientSession session) {
                unsubscribed.add(session);
                if (session == one) {
                    throw new IllegalStateException("the teardown of the application went wrong");
                }
            }

            @Override
            protected void onClosed() {
                closed.incrementAndGet();
            }
        };

        assertTrue(entity.add(one));
        assertTrue(entity.add(two));

        entity.close();

        assertEquals(List.of(one, two), unsubscribed,
                "an entity which is gone is owed to every session it held");
        assertEquals(1, closed.get());
    }

    @Test
    void shouldAddAndRemoveSessions() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();

        assertEquals(0, subscriptions.numberOfSubscribedSessions());

        assertTrue(subscriptions.add(one));
        assertTrue(subscriptions.add(two));

        assertEquals(2, subscriptions.numberOfSubscribedSessions());
        assertTrue(subscriptions.contains(one));
        assertTrue(subscriptions.contains(two));
        assertEquals(List.of(one, two), subscriptions.subscribed);

        assertTrue(subscriptions.remove(one));
        assertFalse(subscriptions.remove(one));

        assertEquals(1, subscriptions.numberOfSubscribedSessions());
        assertFalse(subscriptions.contains(one));
        assertEquals(List.of(one), subscriptions.unsubscribed);
    }

    @Test
    void shouldRejectRepeatedSubscription() {
        final ClientSession one = sessions.newSession();

        assertTrue(subscriptions.add(one));
        assertFalse(subscriptions.add(one));

        assertEquals(1, subscriptions.numberOfSubscribedSessions());
        assertEquals(List.of(one), subscriptions.subscribed); // the handler fired once only
        assertEquals(List.of(one), subscriptions.repeated);
    }

    @Test
    void shouldPublishToEverySubscriberAndAdvanceTheSequence() {
        final ClientSession one = sessions.newSession();
        final ClientSession two = sessions.newSession();
        subscriptions.add(one);
        subscriptions.add(two);

        final List<ClientSession> delivered = new ArrayList<>();

        assertEquals(0, subscriptions.publicationSequence());
        assertEquals(1, subscriptions.publish(delivered::add));
        assertEquals(2, subscriptions.publish(delivered::add));
        assertEquals(2, subscriptions.publicationSequence());

        assertEquals(List.of(one, two, one, two), delivered);
    }

    @Test
    void shouldReportTheSnapshotSequenceToTheSubscriber() {
        subscriptions.add(sessions.newSession());
        subscriptions.publish(session -> { });
        subscriptions.publish(session -> { });
        subscriptions.add(sessions.newSession());

        assertEquals(List.of(0L, 2L), subscriptions.subscribedAt);
    }

    @Test
    void shouldInvokeTheSubscriptionHandlerInlineBeforeAddReturns() {
        final ClientSession one = sessions.newSession();

        final Thread caller = Thread.currentThread();
        final List<Thread> handlerThreads = new ArrayList<>();

        final EntitySubscriptions inline = new EntitySubscriptions("E") {
            @Override
            protected void onClientSessionSubscribed(final ClientSession session,
                                                     final long publicationSequence) {
                handlerThreads.add(Thread.currentThread());
            }
        };

        assertTrue(handlerThreads.isEmpty());
        inline.add(one);

        // the snapshot must be out by the time add() returns, on the calling thread
        assertEquals(1, handlerThreads.size());
        assertSame(caller, handlerThreads.get(0));
    }

    @Test
    void shouldCloseIdempotentlyAndRejectLaterSubscriptions() {
        final ClientSession one = sessions.newSession();
        subscriptions.add(one);

        subscriptions.close();
        subscriptions.close();

        assertEquals(1, subscriptions.closedCount.get());
        assertEquals(List.of(one), subscriptions.unsubscribed);
        assertEquals(0, subscriptions.numberOfSubscribedSessions());
        assertFalse(subscriptions.contains(one));

        final ClientSession later = sessions.newSession();
        assertFalse(subscriptions.add(later)); // said, not thrown: on the deferred path of Channel a throw
        // is an uncaught failure of an event loop task, and the rest of the batch is never subscribed
        assertEquals(List.of(one), subscriptions.subscribed); // the handler did not fire for it
        assertEquals(0, ClientSessionSubscriptions.of(later).numberOfSubscribedEntities());

        assertFalse(subscriptions.remove(one));
    }

    @Test
    void shouldNotSubscribeAClosedSession() {
        final ClientSession one = sessions.newSession();
        one.close(); // everything which unsubscribes a session which goes away has run by now, so a
        // subscription landing after this one would stay here forever

        assertFalse(subscriptions.add(one));

        assertEquals(0, subscriptions.numberOfSubscribedSessions());
        assertFalse(subscriptions.contains(one));
        assertEquals(List.of(), subscriptions.subscribed);
        assertEquals(List.of(), subscriptions.repeated); // it is not a repeated subscription either
        assertEquals(0, ClientSessionSubscriptions.of(one).numberOfSubscribedEntities());
    }
}
