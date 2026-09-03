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

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.github.green4j.newa.lang.CloseHelper;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

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
            CloseHelper.closeQuiet(session); // it went into the list before it was assembled, and nothing
            // else would ever take it out again. Closing it is the whole unwind: the list, whatever the
            // api attached, the channel, and the terminal event the observer is owed
            throw e;
        }

        return session;
    }

    public void broadcast(final CharSequence text) {
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) { // a session which closed, and left the slot behind
                continue;
            }
            try {
                session.send(text);
            } catch (final Exception cause) {
                session.deliveryFailed(cause); // never throws, and never touches the buffer: whatever
                // was allocated for this session was released before it got here
            }
        }
    }

    public void broadcast(final CharSequence text,
                          final Charset charset) {
        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) {
                continue;
            }
            try {
                session.send(text, charset);
            } catch (final Exception cause) {
                session.deliveryFailed(cause);
            }
        }
    }

    /**
     * Sends the buffer to every open session and takes it over: each session is given a retained
     * duplicate of it, with its own reader index, and the buffer itself is released here.
     *
     * @param text to send. Released here whatever happens to it.
     */
    public void broadcastAndRelease(final ByteBuf text) {
        try {
            final ObjectListReadSafe.Snapshot<ClientSession> snapshot = sessions.snapshot();
            for (int i = 0; i < snapshot.limit(); i++) {
                final ClientSession session = snapshot.get(i);
                if (session == null) {
                    continue;
                }
                try {
                    session.send(text.retainedDuplicate()); // a session consumes the frame it is given,
                    // so one buffer can not be handed to all of them
                } catch (final Exception cause) {
                    session.deliveryFailed(cause); // the duplicate is already released - the session
                    // takes it over the moment it is handed one, failure included
                }
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
