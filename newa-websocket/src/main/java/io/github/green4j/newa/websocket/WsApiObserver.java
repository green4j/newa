/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * The whole life cycle of one client session, from the handshake to the channel going away. The library
 * keeps no metrics of its own: it reports, and what that turns into is yours.
 * <p>
 * One of these observes one session, from {@link #onSessionOpened} to {@link #onSessionClosed}, and
 * {@link WsApiObserverFactory} is what produces them. The session is handed over once, at the start, and
 * never repeated: an observer made per session has somewhere to keep what it needs, and a shared one has
 * taken on that problem knowingly.
 * <p>
 * {@link #onSessionClosed} fires exactly once, however the session ended - closed by the peer, closed by
 * the api because it could not keep up, or closed by the application. Counting sessions never means adding
 * two different events up.
 * <p>
 * The order within one session is:
 * <pre>
 * onSessionOpened -&gt; ( onFrameReceived | onFrameSent )*
 *                 -&gt; [ onWriteBackPressure -&gt; onWriteResumed ]*
 *                 -&gt; [ onReceiveFailed | onWriteFailed ] -&gt; onSessionClosed
 * </pre>
 * <p>
 * A session which ends badly says so here, exactly once, and by the stage which knows what went wrong:
 * {@link #onReceiveFailed} when the application failed to handle a frame, {@link #onWriteFailed} when a
 * frame did not go out. A failure of the channel itself is not a stage of a session and goes to the
 * {@link io.github.green4j.newa.lang.ChannelErrorHandler} instead. There is nothing here which renders an
 * error for the peer: a websocket has no response left to render once the handshake is done, so what a
 * client is told about a bad frame is a frame of the application's own protocol, or a close.
 * <p>
 * {@link io.github.green4j.newa.websocket.subscriptions.SubscriptionsWsApiObserver} extends this with the
 * stages of a session which subscribes.
 * <p>
 * Every method has a no-op default. A call comes from whichever thread did the work: the event loop of the
 * session for what the api does on its own, and the publishing thread for the frames it sends - a broadcast
 * made off the event loop reports on the thread which made it. So an implementation must not block, and
 * must be safe to use from several threads at once.
 */
public interface WsApiObserver {
    /**
     * The handshake is done and the session is fully assembled - whatever the api keeps per session is in
     * place by now. The first stage, and the only one given the session, so whatever a later stage needs is
     * copied here.
     *
     * @param session that was opened
     */
    default void onSessionOpened(ClientSession session) {
    }

    /**
     * A data frame arrived from the peer, before the application saw it - text or binary, and one call per
     * frame rather than per message, so a message which arrives in pieces is reported piece by piece. Fires
     * per frame, so keep it cheap. Nothing is reported for the frames the protocol handles on its own -
     * ping, pong and close.
     *
     * @param bytes of the payload, as it came off the wire
     */
    default void onFrameReceived(int bytes) {
    }

    /**
     * A frame reached the channel. Fires per frame, so keep it cheap.
     *
     * @param bytes of the payload handed over
     */
    default void onFrameSent(int bytes) {
    }

    /**
     * The application threw while handling a frame, and the session is closed because of it - with a
     * {@code 1011}, so the peer knows the server broke rather than that the connection went. The cause is
     * given as it was thrown, and this is the only place it is ever told: the peer gets a status and no
     * text, and nothing of it reaches a decoder or the
     * {@link io.github.green4j.newa.lang.ChannelErrorHandler}.
     * <p>
     * {@link #onSessionClosed} follows, as it does for every session.
     *
     * @param error the {@link Receiver} threw
     */
    default void onReceiveFailed(Throwable error) {
    }

    /**
     * The channel could take no more and the frame did not go out. What follows depends on how the api was
     * built: with {@code withSkipOnBackPressure()} the frame is dropped and the session is kept, and
     * without it the session is closed instead, which {@link #onSessionClosed} then reports as usual.
     *
     * @param bytes of the payload which did not go out
     */
    default void onWriteBackPressure(int bytes) {
    }

    /**
     * The session has caught up after {@link #onWriteBackPressure(int)}. Only reachable when the api was
     * built with {@code withSkipOnBackPressure()} - without it a session which falls behind is closed
     * rather than kept.
     */
    default void onWriteResumed() {
    }

    /**
     * Writing to the channel failed, and the session is closed because of it.
     *
     * @param error that caused it
     */
    default void onWriteFailed(Throwable error) {
    }

    /**
     * The session is gone. The last thing reported about it.
     *
     * @param durationNanos from the session opening to it being closed
     */
    default void onSessionClosed(long durationNanos) {
    }
}
