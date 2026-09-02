package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiObserverFactory;

/**
 * The api {@link SubscriptionWsApiBuilder} builds: a {@link WsApi} which keeps the subscriptions of
 * every session it opens and restores them when the session catches up after falling behind.
 */
final class SubscriptionsWsApi extends WsApi {
    // asked once, when the server is assembled, rather than on every session
    private final boolean subscriptionsObservers;

    SubscriptionsWsApi(final WsApiObserverFactory observers,
                       final Receiver receiver,
                       final String websocketPath,
                       final int pingIntervalMs,
                       final boolean skipOnBackPressure) {
        super(
                observers,
                receiver,
                websocketPath,
                pingIntervalMs,
                skipOnBackPressure
        );

        this.subscriptionsObservers = observers instanceof SubscriptionsWsApiObserverFactory;
    }

    @Override
    protected void onSessionOpened(final ClientSession session) {
        // safe by construction: the observer of this session comes from that same factory, and
        // SubscriptionsWsApiObserverFactory narrows the return type of newObserver, so it cannot have
        // produced anything else
        final SubscriptionsWsApiObserver observer = subscriptionsObservers
                ? (SubscriptionsWsApiObserver) session.observer()
                : null;

        ClientSessionSubscriptions.attach(session, observer);
    }

    @Override
    protected void onSessionClosed(final ClientSession session) {
        ClientSessionSubscriptions.getClientSessionSubscriptions(session).unsubscribeAll();
    }

    @Override
    protected void onWriteResumed(final ClientSession session) {
        ClientSessionSubscriptions.getClientSessionSubscriptions(session).resync();
    }
}
