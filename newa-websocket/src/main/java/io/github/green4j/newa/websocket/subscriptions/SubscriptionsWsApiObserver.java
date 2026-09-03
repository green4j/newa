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

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.WsApiObserver;

/**
 * One session of an api which serves subscriptions - the stages of {@link WsApiObserver}, plus what
 * the session subscribed to and what it was sent again after falling behind.
 * <p>
 * The order within one session is:
 * <pre>
 * onSessionOpened -&gt; ( onSubscribed | onRepeatedSubscription | onUnknownEntity | onUnsubscribed )*
 *                 -&gt; [ onWriteBackPressure -&gt; onWriteResumed -&gt; onResynced ]*
 *                 -&gt; onUnsubscribed* -&gt; onSessionClosed
 * </pre>
 * A session which is still subscribed when it goes away is unsubscribed by the api itself, so the
 * subscriptions it leaves behind are reported rather than lost - before {@link #onSessionClosed} when the
 * session is closed on its own event loop, which is how a channel going away closes one. Closed from
 * another thread, the unsubscriptions are scheduled onto that event loop and may land after it.
 * <p>
 * The {@link EntitySubscriptions} handed over is the live one, owned by its channel: read
 * {@link EntitySubscriptions#entityId()} and whatever else is needed during the call, and keep no
 * reference to it beyond that.
 * <p>
 * Every method has a no-op default. As with {@link WsApiObserver}, a call comes from whichever thread did
 * the work: the event loop of the session for the subscribing and the unsubscribing it asks for, and the
 * thread which closed an entity with {@link Channel#removeEntitySubscriptions(CharSequence)} or
 * {@link Channel#close()} for the {@link #onUnsubscribed} that closing causes.
 */
public interface SubscriptionsWsApiObserver extends WsApiObserver {
    /**
     * The session is subscribed to the entity and its snapshot, if the entity sends one, has been written.
     *
     * @param entity subscribed to
     */
    default void onSubscribed(EntitySubscriptions entity) {
    }

    /**
     * The session was subscribed to the entity already, so nothing changed and no snapshot was sent.
     *
     * @param entity already subscribed to
     */
    default void onRepeatedSubscription(EntitySubscriptions entity) {
    }

    /**
     * {@link Channel#subscribeForKnown(io.github.green4j.newa.websocket.ClientSession, CharSequence)}
     * found no entity of that id, so nothing was subscribed and nothing was created for it either.
     *
     * @param channel asked
     * @param entityId asked for, valid for the duration of this call only
     */
    default void onUnknownEntity(Channel<?> channel,
                                 CharSequence entityId) {
    }

    /**
     * The session is no longer subscribed to the entity - it asked to be, it went away, or the entity
     * itself was closed.
     *
     * @param entity unsubscribed from
     */
    default void onUnsubscribed(EntitySubscriptions entity) {
    }

    /**
     * The snapshots have been re-sent to a session which had frames skipped while it could not keep up,
     * so that the skipped ones leave no hole in its stream. Follows {@link #onWriteResumed()}.
     *
     * @param entities re-synchronized, of every channel the session is subscribed to
     */
    default void onResynced(int entities) {
    }
}
