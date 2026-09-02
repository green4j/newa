package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;

public class SubscriptionWsApiBuilder extends WsApiBuilder<SubscriptionWsApiBuilder> {

    public SubscriptionWsApiBuilder(final int version) {
        super(version);
    }

    public WsApi build() {
        return new SubscriptionsWsApi(
                observers,
                receiver,
                websocketPath(),
                pingIntervalMs,
                skipOnBackPressure
        );
    }
}
