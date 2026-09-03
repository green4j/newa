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

package io.github.green4j.newa.example.ws;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;
import io.github.green4j.newa.websocket.subscriptions.SubscriptionsWsApiObserver;
import io.github.green4j.newa.websocket.subscriptions.SubscriptionsWsApiObserverFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prints what happens to every session. An observer per session, so it has somewhere to keep the
 * totals the api itself does not count.
 */
public class StdOutWsApiObserverFactory implements SubscriptionsWsApiObserverFactory {

    @Override
    public SubscriptionsWsApiObserver newObserver() {
        return new StdOutWsApiObserver();
    }

    private static final class StdOutWsApiObserver implements SubscriptionsWsApiObserver {
        private final AtomicLong framesSent = new AtomicLong(); // a broadcast reports from
        private final AtomicLong bytesSent = new AtomicLong(); // the thread which made it

        private volatile String session;

        @Override
        public void onSessionOpened(final ClientSession opened) {
            session = opened.toString();

            System.out.printf("A new session opened: %s%n", session);
        }

        @Override
        public void onFrameSent(final int bytes) {
            framesSent.incrementAndGet();
            bytesSent.addAndGet(bytes);
        }

        @Override
        public void onWriteBackPressure(final int bytes) {
            System.out.printf("Slow consumer detected, %d bytes did not go out: %s%n", bytes, session);
        }

        @Override
        public void onWriteResumed() {
            System.out.printf("The session caught up: %s%n", session);
        }

        @Override
        public void onResynced(final int entities) {
            System.out.printf("%d entities re-synchronized for the session: %s%n", entities, session);
        }

        @Override
        public void onReceiveFailed(final Throwable error) {
            // the cause as the application threw it, and the only place it is told: the peer gets a
            // 1011 close and no text
            System.out.printf("Handling a frame failed with %s for the session: %s%n", error, session);
        }

        @Override
        public void onWriteFailed(final Throwable error) {
            System.out.printf("Writing failed with %s for the session: %s%n", error, session);
        }

        @Override
        public void onSubscribed(final EntitySubscriptions entity) {
            System.out.printf("Subscribed to %s: %s%n", entity.entityId(), session);
        }

        @Override
        public void onUnknownEntity(final io.github.green4j.newa.websocket.subscriptions.Channel<?> channel,
                                    final CharSequence entityId) {
            System.out.printf("No entity %s to subscribe to: %s%n", entityId, session);
        }

        @Override
        public void onUnsubscribed(final EntitySubscriptions entity) {
            System.out.printf("Unsubscribed from %s: %s%n", entity.entityId(), session);
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            System.out.printf(
                    "The following session closed after %d ms, %d frames and %d bytes sent: %s%n",
                    TimeUnit.NANOSECONDS.toMillis(durationNanos),
                    framesSent.get(),
                    bytesSent.get(),
                    session
            );
        }
    }
}
