/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.WsApiObserverFactory;

/**
 * A {@link WsApiObserverFactory} whose observers also see what the session subscribes to. Hand one of
 * these to {@link SubscriptionWsApiBuilder} and every session is observed by a
 * {@link SubscriptionsWsApiObserver} rather than a plain
 * {@link io.github.green4j.newa.websocket.WsApiObserver}.
 */
@FunctionalInterface
public interface SubscriptionsWsApiObserverFactory extends WsApiObserverFactory {
    @Override
    SubscriptionsWsApiObserver newObserver();
}
