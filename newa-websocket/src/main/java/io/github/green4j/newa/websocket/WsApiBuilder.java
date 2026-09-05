/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * Builds an api of plain sessions - what to use unless the sessions have to be kept per entity, which is
 * {@link io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder}. Everything either of them
 * takes is on {@link AbstractWsApiBuilder}.
 */
public class WsApiBuilder extends AbstractWsApiBuilder<WsApiBuilder> {

    public WsApiBuilder(final int version) {
        super(version);
    }

    public WsApi build() {
        return new WsApi(
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
