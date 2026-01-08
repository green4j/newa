package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.WsApiListener;

class ChannelWsApiListener implements WsApiListener {
    static ClientSessionSubscriptions getClientSessionSubscriptions(final ClientSession session) {
        final ClientSessionSubscriptions subscriptions = session.getUserData();
        if (subscriptions == null) {
            throw new IllegalStateException("ClientSessionSubscriptions not found in the user data for the session: "
                    + session + ". Please, make sure you have properly constructed WebApi. "
                    + "For example, use SubscriptionWsApiBuilder.");
        }
        return subscriptions;
    }

    private final WsApiListener delegate;

    ChannelWsApiListener(final WsApiListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onSessionOpened(final ClientSession session) {
        session.putUserData(
                new ClientSessionSubscriptions(session)
        );

        if (delegate != null) {
            delegate.onSessionOpened(session);
        }
    }

    @Override
    public void onSessionClosed(final ClientSession session) {
        final ClientSessionSubscriptions subscriptions = getClientSessionSubscriptions(session);

        subscriptions.unsubscribeAll();

        if (delegate != null) {
            delegate.onSessionClosed(session);
        }
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
        if (delegate != null) {
            delegate.onWriteBackPressure(session);
        }
    }
}
