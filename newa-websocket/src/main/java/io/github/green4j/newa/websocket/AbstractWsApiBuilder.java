/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * Everything an api takes, whichever kind is being built: the handshake path, the receivers, the keep-alive
 * pair, the observers, and what to do with a session which cannot keep up.
 * <pre>{@code
 * WsApi api = new WsApiBuilder(1)                     // version 1, so the path is /websocket/v1
 *         .withPathPrefix("ws")                       // ... or /ws/v1
 *         .withTextReceiver((session, message, last) -> session.sendText(message))
 *         .build();
 * }</pre>
 * The defaults hold a session which says nothing: it is pinged every 30 s and closed after 90 s of silence,
 * which is three missed pings. Either takes 0 to turn it off.
 *
 * @param <B> the builder itself, so a chain keeps its type.
 */
public abstract class AbstractWsApiBuilder<B extends AbstractWsApiBuilder<B>> {
    /**
     * How often an idle session is pinged unless {@link #withPingIntervalMs(int)} says otherwise.
     */
    public static final int DEFAULT_PING_INTERVAL_MS = 30_000;

    /**
     * How long a session may hear nothing at all from its peer before it is closed, unless
     * {@link #withReadTimeoutMs(int)} says otherwise. Three missed pings.
     */
    public static final int DEFAULT_READ_TIMEOUT_MS = 90_000;

    protected final int version;

    protected WsApiObserverFactory observers;
    protected Receiver.Text textReceiver;
    protected Receiver.Binary binaryReceiver;
    protected String pathPrefix = "websocket";
    protected int pingIntervalMs = DEFAULT_PING_INTERVAL_MS;
    protected int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    protected boolean skipOnBackPressure;

    protected AbstractWsApiBuilder(final int version) {
        this.version = version;
    }

    /**
     * Sets what the api asks for an observer of every session it opens. Nothing is observed without it.
     *
     * @param observers the factory of the observers, null to observe nothing.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withObservers(final WsApiObserverFactory observers) {
        this.observers = observers;
        return (B) this;
    }

    /**
     * Keeps a session which can not keep up instead of closing it: while its channel stays
     * unwritable the frames are skipped, and once it catches up the subscriptions layer
     * re-sends a snapshot, so the skipped frames leave no hole in its stream.
     *
     * <p>Applies to the whole api and is off by default. Nothing here can verify the
     * promise it makes, so this call is the one place where it has to be kept: turn it on
     * only if every subscription served by this api restores a session with the snapshot of
     * {@link io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions}. A channel
     * relaying standalone signals has no state to re-send, so a frame skipped there is lost
     * for good and its client must be disconnected instead. Marking it per channel would not
     * help - one such frame disconnects the client anyway, whatever the other channels do.
     *
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withSkipOnBackPressure() {
        this.skipOnBackPressure = true;
        return (B) this;
    }

    /**
     * Sets what every session of this api hands its inbound text frames to. Without one nothing takes text
     * and a text frame is answered with a {@code 1003}, which is all a broadcasting api needs.
     *
     * <p>It lives here, next to the rest of what the application plugs in, for the same reason the handles
     * of a rest api live on the rest api builder: it is what handles what comes in. Note the consequence -
     * the receiver is built before the api, so a receiver which wants to call
     * {@link WsApi#broadcastText(CharSequence)} cannot simply capture it. Subclass {@link WsApi} and
     * override {@link WsApi#textReceiver()} when a receiver needs the api it belongs to.
     *
     * @param textReceiver told about every text frame, null to take none.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withTextReceiver(final Receiver.Text textReceiver) {
        this.textReceiver = textReceiver;
        return (B) this;
    }

    /**
     * Sets what every session of this api hands its inbound binary frames to, on the same terms
     * {@link #withTextReceiver(Receiver.Text)} sets the other half: without one a binary frame is answered
     * with a {@code 1003}, and {@link WsApi#binaryReceiver()} is what a subclass overrides when a receiver
     * needs the api it belongs to.
     *
     * @param binaryReceiver told about every binary frame, null to take none.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withBinaryReceiver(final Receiver.Binary binaryReceiver) {
        this.binaryReceiver = binaryReceiver;
        return (B) this;
    }

    @SuppressWarnings("unchecked")
    public B withPathPrefix(final String pathPrefix) {
        this.pathPrefix = pathPrefix;
        return (B) this;
    }

    /**
     * Sets how often a session which has been idle is pinged. This is what creates the traffic a read
     * timeout waits for - a client which only ever listens sends nothing else, and its pong is what proves
     * it is still there. Pairs with {@link #withReadTimeoutMs(int)}, which is what actually closes a
     * session: on its own a ping notices a dead peer no sooner than the send buffer fills.
     *
     * @param pingIntervalMs between pings of an idle session, {@link #DEFAULT_PING_INTERVAL_MS} by
     *                       default, 0 to send none.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withPingIntervalMs(final int pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
        return (B) this;
    }

    /**
     * Sets how long the peer may send nothing at all before the session is closed. Any inbound frame
     * counts, a pong included, which is why it takes {@link #withPingIntervalMs(int)} to keep an otherwise
     * silent listener alive: without a ping this closes healthy subscribers, and without this a ping
     * closes nothing.
     *
     * @param readTimeoutMs of silence from the peer, {@link #DEFAULT_READ_TIMEOUT_MS} by default, 0 to
     *                      wait for as long as the peer likes.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withReadTimeoutMs(final int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return (B) this;
    }

    protected String websocketPath() {
        final StringBuilder result = new StringBuilder("/v").append(version);
        if (pathPrefix != null) {
            final String pp = pathPrefix.trim();
            if (!pp.isEmpty()) {
                result.insert(0, pp);
                if (!pp.startsWith("/")) {
                    result.insert(0, "/");
                }
            }
        }
        return result.toString();
    }

    public abstract WsApi build();

}
