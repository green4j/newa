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
import io.github.green4j.newa.websocket.ClientSessions;
import io.github.green4j.newa.websocket.ClientSessionsListener;
import io.github.green4j.newa.websocket.WritingResult;
import io.github.green4j.newa.websocket.WsApiObserverFactory;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds real ClientSessions on top of EmbeddedChannel, so that the subscription classes
 * can be tested without a network. EmbeddedChannel reports inEventLoop() == true for any
 * thread, which is what Channel.subscribe() expects on its inline path.
 */
final class TestSessions implements ClientSessionsListener, WritingResult {
    private final ClientSessions sessions;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    // narrowed once, the way SubscriptionsWsApi narrows it
    private final boolean subscriptionsObservers;

    TestSessions() {
        this(null);
    }

    TestSessions(final WsApiObserverFactory observers) {
        this.subscriptionsObservers = observers instanceof SubscriptionsWsApiObserverFactory;
        this.sessions = new ClientSessions(this, observers);
    }

    ClientSession newSession() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        synchronized (channels) {
            channels.add(channel);
        }

        final ClientSession session = sessions.newSession(
                new ClientSessionContext(
                        this,
                        null,
                        channel,
                        0 // no pinger
                )
        );

        return session;
    }

    void closeAll() {
        synchronized (channels) {
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            channels.clear();
        }
    }

    @Override
    public void onSessionOpened(final ClientSession session) {
        // the subscription API expects the per-session bookkeeping to be in place, exactly where
        // SubscriptionsWsApi puts it
        ClientSessionSubscriptions.attach(
                session,
                subscriptionsObservers ? (SubscriptionsWsApiObserver) session.observer() : null
        );
    }

    @Override
    public void onSessionClosed(final ClientSession session) {
        ClientSessionSubscriptions.getClientSessionSubscriptions(session).unsubscribeAll();
    }

    @Override
    public void onWriteSuccess(final ClientSession session) {
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
    }

    @Override
    public void onWriteError(final ClientSession session, final Throwable error) {
    }
}
