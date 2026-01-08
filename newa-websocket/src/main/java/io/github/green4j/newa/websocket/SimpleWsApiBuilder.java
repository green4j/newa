package io.github.green4j.newa.websocket;

public class SimpleWsApiBuilder extends WsApiBuilder<SimpleWsApiBuilder> {

    public SimpleWsApiBuilder(final int version) {
        super(version);
    }

    public WsApi build() {
        return new WsApi(
                listener,
                websocketPath(),
                pingIntervalMs
        );
    }
}
