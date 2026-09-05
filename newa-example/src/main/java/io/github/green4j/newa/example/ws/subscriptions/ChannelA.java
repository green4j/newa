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
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

public class ChannelA extends Channel<ChannelA.ChannelAEntitySubscriptions> {
    @Override
    protected ChannelAEntitySubscriptions newEntitySubscriptions(final String entityId) {
        return new ChannelAEntitySubscriptions(entityId);
    }

    public static class ChannelAEntitySubscriptions extends EntitySubscriptions {
        private static final int NO_VALUE = -1;

        private volatile int lastValue = NO_VALUE; // written before publish(), read by a snapshot

        ChannelAEntitySubscriptions(final String entityId) {
            super(entityId);
        }

        // The state must be mutated before the fan-out, that ordering is what makes
        // the update visible to a session subscribing at the very same moment.
        void publishValue(final int value) {
            lastValue = value;

            if (isEmpty()) {
                return; // nothing to render the frame for. Skipping publish() skips the bump of the
                // publication sequence too, so what a session subscribing right now synchronizes with
                // is the volatile write of lastValue above - and nothing else.
            }

            // rendered once, every session gets a duplicate of it
            publishTextAndRelease(Unpooled.copiedBuffer(entityId + "=" + value, CharsetUtil.UTF_8));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            System.out.printf("%s subscribed to ChannelA@%s%n", session.toString(), entityId);

            final int value = lastValue;
            if (value != NO_VALUE) { // the snapshot goes out before any concurrent update
                session.send(entityId + "=" + value + " S@" + publicationSequence);
            }
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            System.out.printf("%s unsubscribed from ChannelA@%s%n", session.toString(), entityId);
        }
    }
}
