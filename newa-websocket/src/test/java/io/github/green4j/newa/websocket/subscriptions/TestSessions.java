/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
    // recorded rather than acted on: closing a session which failed is the policy of WsApi, and these
    // tests are about what the subscription classes report, not about what an api makes of it
    private final List<ClientSession> writeErrors = new ArrayList<>();

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
        synchronized (writeErrors) {
            writeErrors.add(session);
        }
    }

    List<ClientSession> writeErrors() {
        synchronized (writeErrors) {
            return new ArrayList<>(writeErrors);
        }
    }
}
