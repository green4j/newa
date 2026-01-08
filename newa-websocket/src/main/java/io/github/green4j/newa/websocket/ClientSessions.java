package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class ClientSessions implements ClientSessionFactory {
    private final ClientSessionsListener listener;

    private volatile List<ClientSession> sessions = new ArrayList<>(); // guarded by this

    public ClientSessions(final ClientSessionsListener listener) {
        this.listener = listener;
    }

    @Override
    public final ClientSession newSession(final ClientSessionContext context) {
        final ClientSession session = new ClientSession(this, context);

        synchronized (this) {
            final List<ClientSession> newSessions = new ArrayList<>(sessions);
            newSessions.add(session);
            sessions = newSessions;
        }

        listener.onSessionOpened(session);

        return session;
    }

    public void broadcast(final CharSequence text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text); // TODO: wrap with try/catch?
        }
    }

    public void broadcast(final CharSequence text,
                          final Charset charset) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text, charset); // TODO: wrap with try/catch?
        }
    }

    public void broadcast(final ByteBuf text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final List<ClientSession> currentSessions = sessions;
        for (int i = 0; i < currentSessions.size(); i++) {
            currentSessions.get(i).send(text); // TODO: wrap with try/catch?
        }
    }

    void onClientSessionClosed(final ClientSession session) {
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
