/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.AbstractWsApiBuilder;
import io.github.green4j.newa.websocket.WsApi;

/**
 * Builds an api whose sessions can subscribe: it attaches the per-session bookkeeping every
 * {@link Channel} expects and unsubscribes a session which goes away, without which
 * {@code Channel.subscribe} throws {@link IllegalStateException}.
 * <p>
 * Everything else it takes is {@link AbstractWsApiBuilder}'s, and one thing is its own: the observers may be
 * a {@link SubscriptionsWsApiObserverFactory}, whose observers also see what a session subscribed to.
 */
public class SubscriptionWsApiBuilder extends AbstractWsApiBuilder<SubscriptionWsApiBuilder> {

    public SubscriptionWsApiBuilder(final int version) {
        super(version);
    }

    public WsApi build() {
        return new SubscriptionsWsApi(
                observers,
                textReceiver,
                binaryReceiver,
                websocketPath(),
                pingIntervalMs,
                readTimeoutMs,
                skipOnBackPressure
        );
    }
}
