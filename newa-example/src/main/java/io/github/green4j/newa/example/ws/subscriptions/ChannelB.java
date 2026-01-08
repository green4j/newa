package io.github.green4j.newa.example.ws.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.subscriptions.Channel;
import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;

public class ChannelB extends Channel<ChannelB.ChannelBEntitySubscriptions> {
    @Override
    protected ChannelBEntitySubscriptions newEntitySubscriptions(final String entityId) {
        return new ChannelBEntitySubscriptions(entityId);
    }

    public static class ChannelBEntitySubscriptions extends EntitySubscriptions {
        ChannelBEntitySubscriptions(final String entityId) {
            super(entityId);
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session) {
            System.out.printf("%s subscribed to ChannelB@%s%n", session.toString(), entityId);
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            System.out.printf("%s unsubscribed from ChannelB@%s%n", session.toString(), entityId);
        }
    }
}
