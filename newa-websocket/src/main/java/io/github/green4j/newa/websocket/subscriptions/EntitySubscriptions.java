package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EntitySubscriptions implements Closeable {
    protected final String entityId;

    private final List<ClientSession> subscribedSessions = new ArrayList<>(); // guarded by this
    private volatile int numberOfSubscribedSessions; // guarded by this for mutation

    private boolean closed; // guarded by this

    public EntitySubscriptions(final String entityId) {
        this.entityId = entityId;
    }

    public final String entityId() {
        return entityId;
    }

    public final int numberOfSubscribedSessions() {
        return numberOfSubscribedSessions;
    }

    public final boolean add(final ClientSession session) {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("EntitySubscriptions closed");
            }

            if (subscribedSessions.contains(session)) { // O(n), but we should be OK for practical use-cases
                onClientSessionRepeatedSubscriptionTry(session);
                return false;
            }

            subscribedSessions.add(session);
            numberOfSubscribedSessions++;
            onClientSessionSubscribed(session);
        }
        return true;
    }

    public final boolean contains(final ClientSession session) {
        synchronized (this) {
            return subscribedSessions.contains(session);
        }
    }

    public final boolean remove(final ClientSession session) {
        synchronized (this) {
            if (subscribedSessions.remove(session)) {
                numberOfSubscribedSessions--;
                onClientSessionUnsubscribed(session);
                return true;
            }
            return false;
        }
    }

    public final int forEachSession(final Consumer<ClientSession> consumer) {
        synchronized (this) {
            final List<ClientSession> sessions = subscribedSessions;

            for (int i = 0; i < sessions.size(); i++) {
                final ClientSession session = sessions.get(i);
                consumer.accept(session);
            }
            return sessions.size();
        }
    }

    public final int forEachSessionNoSynchronized(final Consumer<ClientSession> consumer) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException("Must be called under synchronized lock");
        }

        final List<ClientSession> sessions = subscribedSessions;

        for (int i = 0; i < sessions.size(); i++) {
            final ClientSession session = sessions.get(i);
            consumer.accept(session);
        }
        return sessions.size();
    }

    @Override
    public final void close() {
        final List<ClientSession> sessions;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;

            sessions = new ArrayList<>(subscribedSessions);
            subscribedSessions.clear();
            numberOfSubscribedSessions = 0;
        }

        try {
            for (int i = 0; i < sessions.size(); i++) {
                final ClientSession session = sessions.get(i);
                onClientSessionUnsubscribed(session);
            }
        } finally { // in case an unexpected exception happened in onClientSessionUnsubscribed(session);
            onClosed();
        }
    }

    protected void onClientSessionSubscribed(final ClientSession session) {
    }

    protected void onClientSessionRepeatedSubscriptionTry(final ClientSession session) {
    }

    protected void onClientSessionUnsubscribed(final ClientSession session) {
    }

    protected void onClosed() {
    }
}
