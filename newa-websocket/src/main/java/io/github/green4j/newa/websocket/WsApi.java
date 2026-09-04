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

import io.github.green4j.newa.lang.CloseHelper;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public class WsApi implements ClientSessionFactory, WritingResult {
    private final Receiver receiver;
    private final String websocketPath;
    private final int pingIntervalMs;
    private final int readTimeoutMs;
    private final boolean skipOnBackPressure;
    private final ClientSessions clientSessions;

    /**
     * An api whose read timeout is {@link AbstractWsApiBuilder#DEFAULT_READ_TIMEOUT_MS}. Note the pairing that
     * implies: with a ping interval of 0 nothing gives a session which only listens anything to answer,
     * and it is closed after that timeout however healthy it is. Take the constructor below and say 0 for
     * both when that is what you want.
     *
     * @param observers asked for an observer per session, or null to observe nothing.
     * @param receiver told about every data frame of every session, text or binary, or null for an api
     *                 which only ever sends - one which answers an inbound frame with a 1003, since there
     *                 is nothing here to take it. It is to a frame what a rest handle is to a request,
     *                 which is why it belongs to the api rather than to the handler in front of it.
     * @param websocketPath the path the handshake is served on.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never.
     * @param skipOnBackPressure keeps a session which can not keep up instead of closing it.
     */
    public WsApi(final WsApiObserverFactory observers,
                 final Receiver receiver,
                 final String websocketPath,
                 final int pingIntervalMs,
                 final boolean skipOnBackPressure) {
        this(
                observers,
                receiver,
                websocketPath,
                pingIntervalMs,
                AbstractWsApiBuilder.DEFAULT_READ_TIMEOUT_MS, // the safe default belongs to an api assembled by
                // hand no less than to one built by the builder
                skipOnBackPressure
        );
    }

    /**
     * @param observers asked for an observer per session, or null to observe nothing.
     * @param receiver told about every data frame of every session, text or binary, or null for an api
     *                 which only ever sends - one which answers an inbound frame with a 1003, since there
     *                 is nothing here to take it. It is to a frame what a rest handle is to a request,
     *                 which is why it belongs to the api rather than to the handler in front of it.
     * @param websocketPath the path the handshake is served on.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never. What gives a session which
     *                       only listens something to answer, so that the timeout below can tell it from
     *                       a peer which is gone.
     * @param readTimeoutMs how long a session may hear nothing at all from its peer - any frame counts,
     *                      a pong included - before it is closed. 0 to wait for as long as the peer likes.
     * @param skipOnBackPressure keeps a session which can not keep up instead of closing it.
     */
    public WsApi(final WsApiObserverFactory observers,
                 final Receiver receiver,
                 final String websocketPath,
                 final int pingIntervalMs,
                 final int readTimeoutMs,
                 final boolean skipOnBackPressure) {
        this.receiver = receiver;
        this.websocketPath = websocketPath;
        this.pingIntervalMs = pingIntervalMs;
        this.readTimeoutMs = readTimeoutMs;
        this.skipOnBackPressure = skipOnBackPressure;

        clientSessions = new ClientSessions(
                new ClientSessionsListener() { // the single listener of ClientSessions stays internal:
                    // an api built on top of this one wires itself with the hooks below instead of
                    // taking the slot the observer of the application would need
                    @Override
                    public void onSessionOpened(final ClientSession session) {
                        WsApi.this.onSessionOpened(session);
                    }

                    @Override
                    public void onSessionClosed(final ClientSession session) {
                        WsApi.this.onSessionClosed(session);
                    }
                },
                observers
        );
    }

    /**
     * @return what every session of this api hands its inbound data frames to, null if it has none.
     */
    public Receiver receiver() {
        return receiver;
    }

    public String websocketPath() {
        return websocketPath;
    }

    public int pingIntervalMs() {
        return pingIntervalMs;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    @Override
    public ClientSession newSession(final ClientSessionContext context) {
        return clientSessions.newSession(context);
    }

    public void broadcastText(final CharSequence text) {
        clientSessions.broadcastText(text);
    }

    public void broadcastText(final CharSequence text,
                              final Charset charset) {
        clientSessions.broadcastText(
                text,
                charset
        );
    }

    /**
     * Sends the buffer to every open session as a text frame and takes it over: each session is given a
     * retained duplicate of it, and the buffer itself is released once the fan-out is done.
     *
     * @param text to send. Released here whatever happens to it.
     */
    public void broadcastTextAndRelease(final ByteBuf text) {
        clientSessions.broadcastTextAndRelease(text);
    }

    /**
     * Sends the buffer to every open session as a text frame and leaves it to the caller: each session is
     * given a retained duplicate of it, and the reference of the caller is neither taken nor released.
     *
     * @param text to send. Stays the caller's to release.
     */
    public void broadcastText(final ByteBuf text) {
        clientSessions.broadcastText(text);
    }

    /**
     * Sends the buffer to every open session as a binary frame and takes it over, the way
     * {@link #broadcastTextAndRelease(ByteBuf)} does.
     *
     * @param payload to send. Released here whatever happens to it.
     */
    public void broadcastBinaryAndRelease(final ByteBuf payload) {
        clientSessions.broadcastBinaryAndRelease(payload);
    }

    /**
     * Sends the buffer to every open session as a binary frame and leaves it to the caller, the way
     * {@link #broadcastText(ByteBuf)} does.
     *
     * @param payload to send. Stays the caller's to release.
     */
    public void broadcastBinary(final ByteBuf payload) {
        clientSessions.broadcastBinary(payload);
    }

    @Override
    public void onWriteSuccess(final ClientSession session) {
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
        if (session.isClosed()) {
            return;
        }

        if (skipOnBackPressure) { // the frame is skipped, the session is kept
            if (session.channel().isWritable()) {
                // The channel drained while this frame was being given up on, so the writability event
                // which re-synchronizes the session has already come and gone. Nothing else would fire
                // one, and the frame just skipped would stay a hole in the stream of that session until
                // it happens to fall behind again. Idempotent: writeResumed does nothing for a session
                // which has nothing skipped.
                session.executor().execute(() -> writeResumed(session));
            }
            return;
        }

        CloseHelper.closeQuiet(session);
    }

    @Override
    public void onWriteError(final ClientSession session,
                             final Throwable error) {
        if (session.isClosed()) {
            return;
        }

        final WsApiObserver observer = session.observer();
        if (observer != null) {
            observer.onWriteFailed(error);
        }

        CloseHelper.closeQuiet(session);
    }

    final void writeResumed(final ClientSession session) {
        if (!session.clearLagging()) { // nothing was skipped, nothing to re-synchronize
            return;
        }

        final WsApiObserver observer = session.observer();
        if (observer != null) {
            observer.onWriteResumed();
        }

        onWriteResumed(session);
    }

    /**
     * A session has been opened and whatever it is made of is in place, but its observer has not seen it
     * yet. The place for an api built on top of this one to attach what it keeps per session.
     *
     * @param session that was opened.
     */
    protected void onSessionOpened(final ClientSession session) {
    }

    /**
     * A session is gone, before its observer is told about it. Whatever was attached in
     * {@link #onSessionOpened(ClientSession)} is released here.
     *
     * @param session that was closed.
     */
    protected void onSessionClosed(final ClientSession session) {
    }

    /**
     * A session which had frames skipped while it could not keep up has caught up, and its observer has
     * been told. Reached only when the api was built with
     * {@link AbstractWsApiBuilder#withSkipOnBackPressure()}.
     *
     * @param session that became writable again.
     */
    protected void onWriteResumed(final ClientSession session) {
    }
}
