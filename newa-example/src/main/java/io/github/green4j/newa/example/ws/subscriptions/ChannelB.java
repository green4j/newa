/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

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
        private static final int NO_VALUE = -1;

        private volatile int lastValue = NO_VALUE; // written before publish(), read by a snapshot

        ChannelBEntitySubscriptions(final String entityId) {
            super(entityId);
        }

        // The state must be mutated before the fan-out, that ordering is what makes
        // the update visible to a session subscribing at the very same moment.
        void publishValue(final int value) {
            lastValue = value;
            publish(session -> session.send(entityId + "=" + value));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            System.out.printf("%s subscribed to ChannelB@%s%n", session.toString(), entityId);

            final int value = lastValue;
            if (value != NO_VALUE) { // the snapshot goes out before any concurrent update
                session.send(entityId + "=" + value + " @" + publicationSequence);
            }
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            System.out.printf("%s unsubscribed from ChannelB@%s%n", session.toString(), entityId);
        }
    }
}
