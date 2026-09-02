/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
