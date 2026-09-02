package io.github.green4j.newa.websocket;

public abstract class WsApiBuilder<B extends WsApiBuilder<B>> {
    protected final int version;

    protected WsApiObserverFactory observers;
    protected Receiver receiver;
    protected String pathPrefix = "websocket";
    protected int pingIntervalMs;
    protected boolean skipOnBackPressure;

    protected WsApiBuilder(final int version) {
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
     * Sets what every session of this api hands its inbound text frames to. Nothing is received without
     * one, which is all a broadcasting api needs.
     *
     * <p>It lives here, next to the rest of what the application plugs in, for the same reason the handles
     * of a rest api live on the rest api builder: it is what handles what comes in. Note the consequence -
     * the receiver is built before the api, so a receiver which wants to call
     * {@link WsApi#broadcast(CharSequence)} cannot simply capture it. Subclass {@link WsApi} and implement
     * {@link Receiver} on the subclass when a receiver needs the api it belongs to.
     *
     * @param receiver told about every text frame, null to receive nothing.
     * @return this builder.
     */
    @SuppressWarnings("unchecked")
    public B withReceiver(final Receiver receiver) {
        this.receiver = receiver;
        return (B) this;
    }

    @SuppressWarnings("unchecked")
    public B withPathPrefix(final String pathPrefix) {
        this.pathPrefix = pathPrefix;
        return (B) this;
    }

    @SuppressWarnings("unchecked")
    public B withPingIntervalMs(final int pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
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
