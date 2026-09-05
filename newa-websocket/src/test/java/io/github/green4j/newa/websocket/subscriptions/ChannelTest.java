/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelTest {
    private static final class TestChannel extends Channel<EntitySubscriptions> {
        private final List<String> created = new ArrayList<>();

        @Override
        protected EntitySubscriptions newEntitySubscriptions(final String entityId) {
            created.add(entityId);
            return new EntitySubscriptions(entityId);
        }
    }

    private static void runPendingTasks(final ClientSession session) {
        ((EmbeddedChannel) session.channel()).runPendingTasks();
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
        sessions.closeAll();
    }

    @Test
    void shouldCreateEntitySubscriptionsOnce() {
        final EntitySubscriptions first = channel.getOrCreateEntitySubscriptions("AA");

        assertSame(first, channel.getOrCreateEntitySubscriptions("AA"));
        assertSame(first, channel.getEntitySubscriptions("AA"));
        assertEquals(List.of("AA"), channel.created);
        assertEquals("AA", first.entityId());

        assertNull(channel.getEntitySubscriptions("BB"));
    }

    @Test
    void shouldKeepTheLookupAndTheIterationConsistent() {
        channel.getOrCreateEntitySubscriptions("AA");
        channel.getOrCreateEntitySubscriptions("BB");

        assertFalse(channel.isEmpty());

        final List<String> walked = new ArrayList<>();
        assertEquals(2, channel.forEachSubscription(s -> walked.add(s.entityId())));
        assertEquals(List.of("AA", "BB"), walked);

        // the map and the flat list must be swapped together, never one without the other
        final EntitySubscriptions removed = channel.removeEntitySubscriptions("AA");
        assertEquals("AA", removed.entityId());
        assertNull(channel.getEntitySubscriptions("AA"));

        walked.clear();
        assertEquals(1, channel.forEachSubscription(s -> walked.add(s.entityId())));
        assertEquals(List.of("BB"), walked);

        assertNull(channel.removeEntitySubscriptions("AA"));
    }

    @Test
    void shouldSubscribeAndUnsubscribe() {
        final ClientSession session = sessions.newSession();

        assertEquals(2, channel.subscribe(session, List.<CharSequence>of("AA", "BB"), new ArrayList<>()));
        assertTrue(channel.isSubscribed(session));
        assertTrue(channel.getEntitySubscriptions("AA").contains(session));

        final List<CharSequence> notSubscribed = new ArrayList<>();
        assertEquals(2, channel.unsubscribe(session, List.<CharSequence>of("AA", "BB"), notSubscribed));

        assertTrue(notSubscribed.isEmpty());
        assertFalse(channel.isSubscribed(session));
    }

    @Test
    void shouldReportEntitiesTheSessionWasNotSubscribedTo() {
        final ClientSession session = sessions.newSession();
        channel.subscribe(session, "AA");
        channel.getOrCreateEntitySubscriptions("BB"); // known, but this session is not on it

        final List<CharSequence> notSubscribed = new ArrayList<>();
        assertEquals(
                1,
                channel.unsubscribe(session, List.<CharSequence>of("AA", "BB", "CC"), notSubscribed)
        );
        assertEquals(List.<CharSequence>of("BB", "CC"), notSubscribed);
    }

    @Test
    void shouldReportUnknownEntitiesWhenSubscribingForKnownOnly() {
        final ClientSession session = sessions.newSession();
        channel.getOrCreateEntitySubscriptions("AA");

        final List<CharSequence> unknown = new ArrayList<>();
        assertEquals(
                1,
                channel.subscribeForKnown(session, List.<CharSequence>of("AA", "ZZ"), unknown)
        );

        assertEquals(List.<CharSequence>of("ZZ"), unknown);
        assertNull(channel.getEntitySubscriptions("ZZ")); // nothing was created on the fly
    }

    @Test
    void shouldUnsubscribeTheSessionFromEveryEntity() {
        final ClientSession session = sessions.newSession();
        channel.subscribe(session, List.<CharSequence>of("AA", "BB", "CC"), new ArrayList<>());

        channel.unsubscribeAll(session);
        runPendingTasks(session); // unsubscribeAll is scheduled onto the event loop of the session

        assertFalse(channel.isSubscribed(session));
        assertEquals(0, channel.getEntitySubscriptions("AA").numberOfSubscribedSessions());
        assertEquals(0, channel.getEntitySubscriptions("CC").numberOfSubscribedSessions());
    }

    @Test
    void shouldNotSubscribeAClosedSession() {
        final ClientSession session = sessions.newSession();
        final EntitySubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        session.close(); // the unsubscribing of a session which goes away runs once, and it has run now
        runPendingTasks(session);

        assertEquals(0, channel.subscribe(session, "AA"));
        assertEquals(0, channel.subscribeForKnown(session, "AA"));
        runPendingTasks(session);

        assertFalse(channel.isSubscribed(session));
        assertEquals(0, entity.numberOfSubscribedSessions());
        assertEquals(0, ClientSessionSubscriptions.of(session).numberOfSubscribedEntities());
    }

    @Test
    void shouldCloseIdempotentlyAndRejectLaterEntities() {
        final ClientSession session = sessions.newSession();
        channel.subscribe(session, "AA");

        channel.close();
        channel.close();

        assertTrue(channel.isEmpty());
        assertNull(channel.getEntitySubscriptions("AA"));
        assertThrows(IllegalStateException.class, () -> channel.getOrCreateEntitySubscriptions("BB"));
    }

    @Test
    void shouldStartOnceAndNotAfterClose() {
        channel.start();
        assertThrows(IllegalStateException.class, () -> channel.start());

        final TestChannel closed = new TestChannel();
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.start());
    }
}
