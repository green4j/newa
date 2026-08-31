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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertThrows(IllegalStateException.class, () -> subscriptions.add(sessions.newSession()));
        assertFalse(subscriptions.remove(one));
    }
}
