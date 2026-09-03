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
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiObserverFactory;

/**
 * The api {@link SubscriptionWsApiBuilder} builds: a {@link WsApi} which keeps the subscriptions of
 * every session it opens and restores them when the session catches up after falling behind.
 */
final class SubscriptionsWsApi extends WsApi {
    // asked once, when the server is assembled, rather than on every session
    private final boolean subscriptionsObservers;

    SubscriptionsWsApi(final WsApiObserverFactory observers,
                       final Receiver receiver,
                       final String websocketPath,
                       final int pingIntervalMs,
                       final int readTimeoutMs,
                       final boolean skipOnBackPressure) {
        super(
                observers,
                receiver,
                websocketPath,
                pingIntervalMs,
                readTimeoutMs,
                skipOnBackPressure
        );

        this.subscriptionsObservers = observers instanceof SubscriptionsWsApiObserverFactory;
    }

    @Override
    protected void onSessionOpened(final ClientSession session) {
        // safe by construction: the observer of this session comes from that same factory, and
        // SubscriptionsWsApiObserverFactory narrows the return type of newObserver, so it cannot have
        // produced anything else
        final SubscriptionsWsApiObserver observer = subscriptionsObservers
                ? (SubscriptionsWsApiObserver) session.observer()
                : null;

        ClientSessionSubscriptions.attach(session, observer);
    }

    @Override
    protected void onSessionClosed(final ClientSession session) {
        ClientSessionSubscriptions.getClientSessionSubscriptions(session).unsubscribeAll();
    }

    @Override
    protected void onWriteResumed(final ClientSession session) {
        ClientSessionSubscriptions.getClientSessionSubscriptions(session).resync();
    }
}
