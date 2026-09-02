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
            publishAndRelease(Unpooled.copiedBuffer(entityId + "=" + value, CharsetUtil.UTF_8));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            System.out.printf("%s subscribed to ChannelA@%s%n", session.toString(), entityId);

            final int value = lastValue;
            if (value != NO_VALUE) { // the snapshot goes out before any concurrent update
                session.send(entityId + "=" + value + " @" + publicationSequence);
            }
        }

        @Override
        protected void onClientSessionUnsubscribed(final ClientSession session) {
            System.out.printf("%s unsubscribed from ChannelA@%s%n", session.toString(), entityId);
        }
    }
}
