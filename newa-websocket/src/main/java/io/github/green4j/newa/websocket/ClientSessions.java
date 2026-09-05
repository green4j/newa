/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;

import java.nio.charset.Charset;

/**
 * The open sessions of one api, and the fan-out over them. A session is added when its handshake completes
 * and removed when its channel goes; neither costs a copy of the list, so a storm of clients arriving or
 * reconnecting costs what it is rather than its square.
 * <p>
 * A broadcast walks a snapshot by index without taking a lock, and walks it to the end: half a fan-out is
 * not a state anything can recover from, since the sessions already reached have the frame. A session which
 * fails costs that session - reported and closed the way a failed write is - and the walk goes on.
 * <p>
 * The {@code AndRelease} forms take the buffer over and release it once every session has been given a
 * retained duplicate; the others leave it to the caller, for a frame which is sent again or kept.
 */
public class ClientSessions implements ClientSessionFactory {
    private final ClientSessionsListener listener;
    private final WsApiObserverFactory observers;

    // Neither opening nor closing a session copies it, so a storm of clients arriving or reconnecting
    // costs what it is, not its square. A broadcast walks a snapshot of it by index, taking no lock, and
    // walks it to the end: half a fan-out is not a state anything can recover from, because the sessions
    // already reached have the frame. So a session which throws costs that session - it is reported and
    // closed the way a failed write is - and the walk goes on to the next one.
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

        try {
            if (listener != null) {
                listener.onSessionOpened(session); // whatever the api keeps per session is put in place
                // first, so that the observer below sees a session which is fully assembled
            }

            if (observer != null) {
                observer.onSessionOpened(session);
            }
        } catch (final RuntimeException | Error e) {
            session.closeWith(WebSocketCloseStatus.INTERNAL_SERVER_ERROR); // it went into the list before
            // it was assembled, and nothing else would ever take it out again. Closing it is the whole
            // unwind: the list, whatever the api attached, the channel, and the terminal event the
            // observer is owed. The channel is as new as a channel gets, so the peer is told that the
            // server broke rather than left to read a handshake followed by nothing
            throw e;
        }

        return session;
    }

    public void broadcastText(final CharSequence text) {
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) { // a session which closed, and left the slot behind
                continue;
            }
            try {
                session.sendText(text);
            } catch (final Exception cause) {
                session.deliveryFailed(cause); // never throws, and never touches the buffer: whatever
                // was allocated for this session was released before it got here
            }
        }
    }

    public void broadcastText(final CharSequence text,
                              final Charset charset) {
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) {
                continue;
            }
            try {
                session.sendText(text, charset);
            } catch (final Exception cause) {
                session.deliveryFailed(cause);
            }
        }
    }

    /**
     * Sends the buffer to every open session as a text frame and takes it over: each session is given a
     * retained duplicate of it, with its own reader index, and the buffer itself is released here.
     *
     * @param text to send. Released here whatever happens to it.
     */
    public void broadcastTextAndRelease(final ByteBuf text) {
        broadcastAndRelease(text, false);
    }

    /**
     * Sends the buffer to every open session as a text frame and leaves it to the caller: each session is
     * given a retained duplicate of it, with its own reader index, and the reference of the caller is
     * neither taken nor released, so the buffer can be sent again or kept.
     *
     * @param text to send. Stays the caller's to release.
     */
    public void broadcastText(final ByteBuf text) {
        broadcastAndRelease(text.retain(), false); // the reference taken below is the one added here,
        // so the caller keeps its own
    }

    /**
     * Sends the buffer to every open session as a binary frame and takes it over, the way
     * {@link #broadcastTextAndRelease(ByteBuf)} does.
     *
     * @param payload to send. Released here whatever happens to it.
     */
    public void broadcastBinaryAndRelease(final ByteBuf payload) {
        broadcastAndRelease(payload, true);
    }

    /**
     * Sends the buffer to every open session as a binary frame and leaves it to the caller, the way
     * {@link #broadcastText(ByteBuf)} does.
     *
     * @param payload to send. Stays the caller's to release.
     */
    public void broadcastBinary(final ByteBuf payload) {
        broadcastAndRelease(payload.retain(), true);
    }

    private void broadcastAndRelease(final ByteBuf frame,
                                     final boolean binary) {
        try {
            final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
            for (int i = 0; i < snapshot.limit(); i++) {
                final ClientSession session = snapshot.get(i);
                if (session == null) {
                    continue;
                }
                try {
                    final ByteBuf theirs = frame.retainedDuplicate(); // a session consumes the frame it
                    // is given, so one buffer can not be handed to all of them
                    if (binary) {
                        session.sendBinary(theirs);
                    } else {
                        session.sendText(theirs);
                    }
                } catch (final Exception cause) {
                    session.deliveryFailed(cause); // the duplicate is already released - the session
                    // takes it over the moment it is handed one, failure included
                }
            }
        } finally {
            frame.release();
        }
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
