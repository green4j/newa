/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property the whole lock-free design exists for: a session subscribing while
 * a publisher is running must never miss a publication. Every publication is either
 * already reflected in the snapshot the session takes on subscription, or delivered
 * to it as an update afterwards. Seeing one twice is allowed, losing one is not.
 */
class EntitySubscriptionsConcurrencyTest {
    private static final int PUBLICATIONS = 5_000;
    private static final int SUBSCRIBERS = 8;
    private static final int ITERATIONS = 10;

    private static final class Recorder {
        private final List<Integer> deltas = new ArrayList<>(); // written by the publisher only
        private int snapshot = -1; // written by the subscribing thread only
    }

    private static final class Counter extends EntitySubscriptions {
        private final Map<ClientSession, Recorder> recorders;
        private final boolean viaPublish;

        // Deliberately NOT volatile. Its visibility to a concurrently subscribing session
        // must come from the publication sequence edge inside EntitySubscriptions, and
        // from nowhere else - that is exactly what is under test here.
        private int value;

        Counter(final Map<ClientSession, Recorder> recorders,
                final boolean viaPublish) {
            super("E");
            this.recorders = recorders;
            this.viaPublish = viaPublish;
        }

        void publishValue(final int newValue) {
            value = newValue; // the state must be mutated before the fan-out

            final Consumer<ClientSession> consumer =
                    session -> recorders.get(session).deltas.add(newValue);

            if (viaPublish) {
                publish(consumer);
            } else {
                forEachSession(consumer); // must carry the same barrier
            }
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            recorders.get(session).snapshot = value;
        }
    }

    @Test
    void shouldNeverLoseAPublicationToASessionSubscribingConcurrently() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            runOneRace(true);
        }
    }

    @Test
    void shouldNeverLoseAWalkedUpdateToASessionSubscribingConcurrently() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            runOneRace(false);
        }
    }

    private void runOneRace(final boolean viaPublish) throws InterruptedException {
        final TestSessions sessions = new TestSessions();
        try {
            final Map<ClientSession, Recorder> recorders = new ConcurrentHashMap<>();
            final Counter counter = new Counter(recorders, viaPublish);

            final CountDownLatch start = new CountDownLatch(1);
            final List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < SUBSCRIBERS; i++) {
                final ClientSession session = sessions.newSession();
                recorders.put(session, new Recorder());

                final int spins = i * 41; // spread the subscriptions over the publication run
                final Thread subscriber = new Thread(() -> {
                    awaitQuietly(start);
                    for (int spin = 0; spin < spins; spin++) {
                        Thread.onSpinWait();
                    }
                    counter.add(session);
                }, "subscriber-" + i);

                threads.add(subscriber);
                subscriber.start();
            }

            final Thread publisher = new Thread(() -> {
                awaitQuietly(start);
                for (int value = 1; value <= PUBLICATIONS; value++) {
                    counter.publishValue(value);
                }
            }, "publisher");

            threads.add(publisher);
            publisher.start();

            start.countDown();
            for (int i = 0; i < threads.size(); i++) {
                threads.get(i).join();
            }

            recorders.forEach((session, recorder) -> assertNoGap(recorder));
        } finally {
            sessions.closeAll();
        }
    }

    private static void assertNoGap(final Recorder recorder) {
        final List<Integer> deltas = recorder.deltas;

        // whatever was delivered must be a contiguous ascending run ending at the last publication
        for (int i = 0; i < deltas.size(); i++) {
            assertEquals(
                    deltas.get(0) + i,
                    deltas.get(i),
                    "updates must arrive in order and without holes"
            );
        }
        if (!deltas.isEmpty()) {
            assertEquals(
                    PUBLICATIONS,
                    deltas.get(deltas.size() - 1),
                    "once subscribed, a session must receive every later publication"
            );
        }

        // and everything that was NOT delivered must already be part of the snapshot
        final int firstDelivered = deltas.isEmpty() ? PUBLICATIONS + 1 : deltas.get(0);
        assertTrue(
                recorder.snapshot >= firstDelivered - 1,
                "publication " + (firstDelivered - 1) + " was neither delivered nor present"
                        + " in the snapshot (" + recorder.snapshot + ")"
        );
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
