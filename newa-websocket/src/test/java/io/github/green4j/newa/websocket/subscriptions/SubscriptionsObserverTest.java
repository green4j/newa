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
import io.github.green4j.newa.websocket.WsApiObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a session of an api serving subscriptions reports on top of the stages every session has.
 */
class SubscriptionsObserverTest {
    private static final class Observed implements SubscriptionsWsApiObserver {
        private final List<String> stages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onSessionOpened(final ClientSession session) {
            stages.add("opened");
        }

        @Override
        public void onSubscribed(final EntitySubscriptions entity) {
            stages.add("subscribed:" + entity.entityId());
        }

        @Override
        public void onRepeatedSubscription(final EntitySubscriptions entity) {
            stages.add("repeated:" + entity.entityId());
        }

        @Override
        public void onUnknownEntity(final Channel<?> channel,
                                    final CharSequence entityId) {
            stages.add("unknown:" + entityId);
        }

        @Override
        public void onUnsubscribed(final EntitySubscriptions entity) {
            stages.add("unsubscribed:" + entity.entityId());
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            stages.add("closed");
        }
    }

    private static final class TestChannel extends Channel<EntitySubscriptions> {
        @Override
        protected EntitySubscriptions newEntitySubscriptions(final String entityId) {
            return new EntitySubscriptions(entityId);
        }
    }

    private final List<Observed> observers = Collections.synchronizedList(new ArrayList<>());

    private TestSessions sessions;
    private TestChannel channel;

    private Observed observerOf(final int session) {
        return observers.get(session);
    }

    @BeforeEach
    void setUp() {
        sessions = new TestSessions((SubscriptionsWsApiObserverFactory) () -> {
            final Observed observer = new Observed();
            observers.add(observer);
            return observer;
        });
        channel = new TestChannel();
    }

    @AfterEach
    void tearDown() {
        channel.close();
        sessions.closeAll();
    }

    @Test
    void shouldReportSubscribingAndUnsubscribing() {
        final ClientSession session = sessions.newSession();

        channel.subscribe(session, "AA");
        channel.subscribe(session, "AA"); // the same entity again, nothing changes
        channel.unsubscribe(session, "AA");
        channel.unsubscribe(session, "AA"); // not subscribed anymore, nothing changes

        assertEquals(
                List.of("opened", "subscribed:AA", "repeated:AA", "unsubscribed:AA"),
                observerOf(0).stages
        );
    }

    @Test
    void shouldReportAnEntityWhichIsNotThere() {
        final ClientSession session = sessions.newSession();

        assertEquals(0, channel.subscribeForKnownOnly(session, "ZZ"));

        assertEquals(List.of("opened", "unknown:ZZ"), observerOf(0).stages);
    }

    @Test
    void shouldReportWhatASessionLeavesBehindBeforeItIsClosed() {
        final ClientSession session = sessions.newSession();

        channel.subscribe(session, "AA");
        channel.subscribe(session, "BB");

        session.close();

        final List<String> stages = observerOf(0).stages;
        assertEquals("closed", stages.get(stages.size() - 1),
                "the session closing is the last thing reported about it");
        assertTrue(stages.contains("unsubscribed:AA"));
        assertTrue(stages.contains("unsubscribed:BB"));
    }

    @Test
    void shouldReportAnEntityClosedUnderTheSession() {
        final ClientSession session = sessions.newSession();

        channel.subscribe(session, "AA");
        channel.removeEntitySubscriptions("AA");

        assertEquals(
                List.of("opened", "subscribed:AA", "unsubscribed:AA"),
                observerOf(0).stages
        );
    }

    @Test
    void shouldReportEachSessionOnItsOwn() {
        final ClientSession first = sessions.newSession();
        final ClientSession second = sessions.newSession();

        channel.subscribe(first, "AA");
        channel.subscribe(second, "BB");

        assertEquals(List.of("opened", "subscribed:AA"), observerOf(0).stages);
        assertEquals(List.of("opened", "subscribed:BB"), observerOf(1).stages);
    }

    @Test
    void shouldServeASessionTheFactoryRefusedToObserve() {
        // a factory of the subscriptions kind is free to hand out no observer at all, and the session
        // must go on subscribing as if nothing happened
        final TestSessions unobserved = new TestSessions((SubscriptionsWsApiObserverFactory) () -> null);

        try {
            final ClientSession session = unobserved.newSession();

            assertEquals(1, channel.subscribe(session, "AA"));
            assertTrue(channel.isSubscribed(session));

            assertEquals(0, channel.subscribeForKnownOnly(session, "ZZ"));
            assertEquals(1, channel.unsubscribe(session, "AA"));
            assertFalse(channel.isSubscribed(session));

            session.close();
            assertTrue(session.isClosed());
            assertTrue(observers.isEmpty(), "no observer of this test was ever made");
        } finally {
            unobserved.closeAll();
        }
    }

    @Test
    void shouldLeaveAPlainObserverAloneWithTheStagesOfTheSubscriptions() {
        final List<String> stages = Collections.synchronizedList(new ArrayList<>());

        final TestSessions plainSessions = new TestSessions(() -> new WsApiObserver() {
            @Override
            public void onSessionOpened(final ClientSession session) {
                stages.add("opened");
            }

            @Override
            public void onSessionClosed(final long durationNanos) {
                stages.add("closed");
            }
        });

        try {
            final ClientSession session = plainSessions.newSession();

            channel.subscribe(session, "AA");
            channel.subscribeForKnownOnly(session, "ZZ");
            channel.unsubscribe(session, "AA");

            session.close();

            assertEquals(List.of("opened", "closed"), stages);
        } finally {
            plainSessions.closeAll();
        }
    }
}
