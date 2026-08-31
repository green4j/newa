package io.github.green4j.newa.websocket;

import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public class ClientSessions implements ClientSessionFactory {
    private final ClientSessionsListener listener;
    private final WsApiObserverFactory observers;

    // Neither opening nor closing a session copies it, so a storm of clients arriving or reconnecting
    // costs what it is, not its square. A broadcast walks a snapshot of it by index, taking no lock.
    private final ObjectListReadSafe<ClientSession> sessions = new ObjectListReadSafe<>();

    public ClientSessions(final ClientSessionsListener listener) {
        this(listener, null);
    }

    /**
     * @param listener notified of the sessions coming and going, null for none.
     * @param observers asked for an observer per session, null to observe nothing.
     */
    public ClientSessions(final ClientSessionsListener listener,
                          final WsApiObserverFactory observers) {
        this.listener = listener;
        this.observers = observers;
    }

    @Override
    public final ClientSession newSession(final ClientSessionContext context) {
        final WsApiObserver observer = observers != null
                ? observers.newObserver()
                : null;

        final ClientSession session = new ClientSession(this, context, observer);

        sessions.add(session);

        if (listener != null) {
            listener.onSessionOpened(session); // whatever the api keeps per session is put in place
            // first, so that the observer below sees a session which is fully assembled
        }

        if (observer != null) {
            observer.onSessionOpened(session);
        }

        return session;
    }

    public void broadcast(final CharSequence text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) { // a session which closed, and left the slot behind
                continue;
            }
            session.send(text); // TODO: wrap with try/catch?
        }
    }

    public void broadcast(final CharSequence text,
                          final Charset charset) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) {
                continue;
            }
            session.send(text, charset); // TODO: wrap with try/catch?
        }
    }

    /**
     * Sends the buffer to every open session and takes it over: each session is given a retained
     * duplicate of it, with its own reader index, and the buffer itself is released here.
     *
     * @param text to send. Released here whatever happens to it.
     */
    public void broadcastAndRelease(final ByteBuf text) {
        // TODO: check Thread.currentThread().isInterrupted() while iterating?
        try {
            final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
            for (int i = 0; i < snapshot.limit(); i++) {
                final ClientSession session = snapshot.get(i);
                if (session == null) {
                    continue;
                }
                session.send(text.retainedDuplicate()); // a session consumes the frame it is given,
                // so one buffer can not be handed to all of them
            }
        } finally {
            text.release();
        }
    }

    /**
     * Sends the buffer to every open session and leaves it to the caller: each session is given a retained
     * duplicate of it, with its own reader index, and the reference of the caller is neither taken nor
     * released, so the buffer can be sent again or kept.
     *
     * @param text to send. Stays the caller's to release.
     */
    public void broadcast(final ByteBuf text) {
        broadcastAndRelease(text.retain()); // the reference taken below is the one added here,
        // so the caller keeps its own
    }

    void onClientSessionClosed(final ClientSession session) {
        if (!sessions.remove(session)) {
            return;
        }

        if (listener != null) {
            listener.onSessionClosed(session);
        }
    }
}
