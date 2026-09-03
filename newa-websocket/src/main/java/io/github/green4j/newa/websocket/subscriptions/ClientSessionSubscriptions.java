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

import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.github.green4j.newa.websocket.ClientSession;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What one session is subscribed to. The api built by {@link SubscriptionWsApiBuilder} attaches one of
 * these to every session it opens.
 *
 * <p>The registry is the session's own, and {@link EntitySubscriptions} keeps it up to date itself. Two
 * things follow. Re-synchronizing a session which fell behind, and unsubscribing one which goes away, walk
 * the entities that session actually subscribed to rather than every entity of every channel it ever
 * touched - the difference between the handful it reads and the tens of thousands a channel may hold. And a
 * session added to an entity directly, without going through a {@link Channel}, is unsubscribed on its way
 * out all the same, instead of being left behind in that entity forever.
 */
public final class ClientSessionSubscriptions {
    static ClientSessionSubscriptions getClientSessionSubscriptions(final ClientSession session) {
        final ClientSessionSubscriptions subscriptions = session.getUserData();
        if (subscriptions == null) {
            throw new IllegalStateException("ClientSessionSubscriptions not found in the user data for the session: "
                    + session + ". Please, make sure you have properly constructed WebApi. "
                    + "For example, use SubscriptionWsApiBuilder.");
        }
        return subscriptions;
    }

    /**
     * Lenient on purpose: {@link EntitySubscriptions} is usable on its own, with sessions no api of this
     * package ever attached anything to.
     *
     * @param session to look them up for.
     * @return the subscriptions of the session, null if it keeps none.
     */
    static ClientSessionSubscriptions of(final ClientSession session) {
        final Object subscriptions = session.getUserData();
        if (!(subscriptions instanceof ClientSessionSubscriptions)) {
            return null;
        }
        return (ClientSessionSubscriptions) subscriptions;
    }

    static ClientSessionSubscriptions attach(final ClientSession session,
                                             final SubscriptionsWsApiObserver observer) {
        final ClientSessionSubscriptions result = new ClientSessionSubscriptions(session, observer);
        session.putUserData(result);
        return result;
    }

    static boolean onSessionEventLoop(final ClientSession session) {
        return session.channel().eventLoop().inEventLoop();
    }

    private final ClientSession session;
    private final SubscriptionsWsApiObserver observer; // null when the session is not observed

    // Walked by index, without a lock and without an iterator, by everything a session does to all of
    // its subscriptions at once - re-synchronizing them and unsubscribing them.
    private final ObjectListReadSafe<EntitySubscriptions> subscribedEntities = new ObjectListReadSafe<>();

    private final AtomicReference<Object> userData = new AtomicReference<>();

    private ClientSessionSubscriptions(final ClientSession session,
                                       final SubscriptionsWsApiObserver observer) {
        this.session = session;
        this.observer = observer;
    }

    public ClientSession session() {
        return session;
    }

    /**
     * @return the number of the entities the session is subscribed to, across every channel.
     */
    public int numberOfSubscribedEntities() {
        return subscribedEntities.size();
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData() {
        return (T) userData.get();
    }

    @SuppressWarnings("unchecked")
    public <T> T putUserData(final T userData) {
        return (T) this.userData.getAndSet(userData);
    }

    @SuppressWarnings("unchecked")
    public <T> T putUserDataIfAbsent(final T userData) {
        final Object old = this.userData.compareAndExchange(null, userData);
        return old == null ? userData : (T) old;
    }

    SubscriptionsWsApiObserver observer() {
        return observer;
    }

    boolean isSubscribedTo(final EntitySubscriptions entity) {
        return subscribedEntities.contains(entity);
    }

    void onSubscribed(final EntitySubscriptions entity) {
        subscribedEntities.add(entity);
    }

    void onUnsubscribed(final EntitySubscriptions entity) {
        subscribedEntities.remove(entity);
    }

    void resync() {
        final ObjectListReadSafe.Snapshot<EntitySubscriptions> snapshot = subscribedEntities.snapshot();

        int entities = 0;
        for (int i = 0; i < snapshot.limit(); i++) {
            final EntitySubscriptions entity = snapshot.get(i);
            if (entity == null) { // an entity this session unsubscribed from, slot and all
                continue;
            }
            try {
                entity.resync(session);
                entities++;
            } catch (final Exception cause) { // the snapshot of this entity never went out, so this
                session.deliveryFailed(cause); // session has a hole nothing else would fill. It is
                // closed, and the remaining entities are still re-synchronized rather than left behind
            }
        }

        if (observer != null) {
            observer.onResynced(entities);
        }
    }

    // Runs inline on the event loop of the session and is scheduled onto it from anywhere else - the same
    // contract subscribing and unsubscribing keep. Being inline is what puts the unsubscriptions of a
    // session which is closing before the terminal event of its observer: a session closed on its own event
    // loop, which is how the channel going away closes one, is done unsubscribing by the time close()
    // reports it.
    void unsubscribeAll() {
        if (!onSessionEventLoop(session)) {
            session.executor().execute(this::unsubscribeAll);
            return;
        }

        // taken out in one go, so that the removals below find nothing left to take out one at a time
        final ObjectListReadSafe.Snapshot<EntitySubscriptions> gone = subscribedEntities.clear();
        for (int i = 0; i < gone.limit(); i++) {
            final EntitySubscriptions entity = gone.get(i);
            if (entity == null) {
                continue;
            }
            try {
                entity.remove(session);
            } catch (final Exception ignore) { // teardown: one entity whose application hook throws must
                // not leave this session subscribed to every entity after it in the list
            }
        }
    }
}
