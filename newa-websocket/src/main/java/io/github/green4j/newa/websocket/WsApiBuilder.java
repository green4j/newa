package io.github.green4j.newa.websocket;

public abstract class WsApiBuilder<B extends WsApiBuilder<B>> {
    protected final int version;

    protected WsApiListener listener;
    protected String pathPrefix = "websocket";
    protected int pingIntervalMs;

    protected WsApiBuilder(final int version) {
        this.version = version;
    }

    @SuppressWarnings("unchecked")
    public B withListener(final WsApiListener listener) {
        this.listener = listener;
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
