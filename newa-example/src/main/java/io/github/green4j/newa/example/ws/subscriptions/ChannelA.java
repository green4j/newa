package io.github.green4j.newa.example.ws.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.subscriptions.Channel;
import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;

public class ChannelA extends Channel<ChannelA.ChannelAEntitySubscriptions> {
    @Override
    protected ChannelAEntitySubscriptions newEntitySubscriptions(final String entityId) {
        return new ChannelAEntitySubscriptions(entityId);
    }

    public static class ChannelAEntitySubscriptions extends EntitySubscriptions {
        ChannelAEntitySubscriptions(final String entityId) {
            super(entityId);
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session) {
            System.out.printf("%s subscribed to ChannelA@%s%n", session.toString(), entityId);
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            System.out.printf("%s unsubscribed from ChannelA@%s%n", session.toString(), entityId);
        }
    }
}
