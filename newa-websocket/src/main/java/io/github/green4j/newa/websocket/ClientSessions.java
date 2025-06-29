package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

// TODO: Make it a closable?
public class ClientSessions implements ClientSessionsStatistics {
    private volatile List<ClientSession> sessions = new ArrayList<>(); // guarded by this

    private final ClientSessionsListener listener;

    private volatile int numberOfSessionsTotal;

    public ClientSessions(final ClientSessionsListener listener) {
        this.listener = listener;
    }

    public final ClientSession newClientSession(final ClientSessionContext context) {
        final ClientSession session = new ClientSession(this, context);

        synchronized (this) {
            final List<ClientSession> newSessions = new ArrayList<>(sessions);
            newSessions.add(session);
            sessions = newSessions;

            numberOfSessionsTotal++;
        }

        listener.onSessionOpened(session);

        return session;
    }

    @Override
    public final int numberOfSessions() {
        return sessions.size();
    }

    @Override
    public final int numberOfSessionsTotal() {
        return numberOfSessionsTotal;
    }

    public void send(final CharSequence text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text); // TODO: wrap with try/catch?
        }
    }

    public void send(final CharSequence text, final Charset charset) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text, charset); // TODO: wrap with try/catch?
        }
    }

    public void send(final ByteBuf text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text); // TODO: wrap with try/catch?
        }
    }

    void closeClientSession(final ClientSession session) {
        final boolean removed;
        synchronized (this) {
            final List<ClientSession> newSessions = new ArrayList<>(sessions);
            removed = newSessions.remove(session);
            sessions = newSessions;
        }

        if (!removed) {
            return;
        }

        listener.onSessionClosed(session);
    }
}
