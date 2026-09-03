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

import io.github.green4j.newa.collections.CharSequenceToObjectMapConcurrent;
import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.github.green4j.newa.lang.CloseHelper;
import io.github.green4j.newa.websocket.ClientSession;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.green4j.newa.websocket.subscriptions.ClientSessionSubscriptions.getClientSessionSubscriptions;
import static io.github.green4j.newa.websocket.subscriptions.ClientSessionSubscriptions.onSessionEventLoop;

/**
 * A channel owning an {@link EntitySubscriptions} per entity.
 *
 * <p>Lookups and iterations take no lock. The only lock left guards creation and removal of an entity -
 * a rare path, usually taken at start-up - and it keeps the exactly-once contract of
 * {@link #newEntitySubscriptions(String)}. It holds no more than the entry it adds, so filling a channel
 * with a hundred thousand entities costs what they are rather than their square. Nothing on the publishing
 * path takes a lock.
 *
 * <p>{@link #subscribe} and {@link #unsubscribe} run inline when called on the event loop
 * of the session, otherwise they are scheduled onto it, the same way {@link #unsubscribeAll}
 * is. Being on that event loop is what lets a snapshot sent from
 * {@link EntitySubscriptions#onClientSessionSubscribed(ClientSession, long)} precede
 * every concurrent update.
 */
public abstract class Channel<S extends EntitySubscriptions> implements Closeable {
    // An entity goes into the list before it goes into the map, and leaves the map before it leaves the
    // list, so whatever walks the entities of this channel never misses one which can already be found by
    // its id. Both are written under the lock of this channel and read without it.
    private final ObjectListReadSafe<S> entities = new ObjectListReadSafe<>();
    private final CharSequenceToObjectMapConcurrent<S> entitiesById = new CharSequenceToObjectMapConcurrent<>();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    protected Channel() {
    }

    public final void start() {
        if (closed.get()) {
            throw new IllegalStateException("Closed");
        }

        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Already started");
        }

        onStarted();
    }

    public final int subscribe(final ClientSession session,
                               final CharSequence entityId) {
        return subscribe(
                session,
                List.of(entityId),
                new ArrayList<>()
        );
    }

    /**
     * The returned value and {@code unknownEntityIds} are only meaningful when called
     * on the event loop of the session. Called from any other thread the work is scheduled
     * onto that event loop, 0 is returned and the outcome is reported through the callbacks
     * of {@link EntitySubscriptions} only.
     *
     * <p>A closed session subscribes to nothing and 0 comes back, whether it was closed before the call or
     * while the work was on its way to the event loop.
     *
     * @param session the session to apply the change to.
     * @param entityIds the ids to apply the change for.
     * @param unknownEntityIds collects the ids no EntitySubscriptions is known for.
     * @return the number of the ids an EntitySubscriptions was found or created for, which counts an id the
     *         session was subscribed to already.
     */
    public final int subscribe(final ClientSession session,
                               final List<CharSequence> entityIds,
                               final List<CharSequence> unknownEntityIds) {
        return doSubscribe(
                session,
                entityIds,
                unknownEntityIds,
                false
        );
    }

    public final int subscribeForKnown(final ClientSession session,
                                       final CharSequence entityId) {
        return subscribeForKnown(
                session,
                List.of(entityId),
                new ArrayList<>()
        );
    }

    /**
     * @see #subscribe(ClientSession, List, List) for the threading contract.
     *
     * @param session the session to apply the change to.
     * @param entityIds the ids to apply the change for.
     * @param unknownEntityIds collects the ids no EntitySubscriptions is known for.
     * @return the number of the ids an EntitySubscriptions was found for, which counts an id the session
     *         was subscribed to already.
     */
    public final int subscribeForKnown(final ClientSession session,
                                       final List<CharSequence> entityIds,
                                       final List<CharSequence> unknownEntityIds) {
        return doSubscribe(
                session,
                entityIds,
                unknownEntityIds,
                true
        );
    }

    private int doSubscribe(final ClientSession session,
                            final List<CharSequence> entityIds,
                            final List<CharSequence> unknownEntityIds,
                            final boolean knownOnly) {
        // Read once before the scheduling below and once more when the scheduled task gets here on the
        // event loop. The second read is the one which matters: a session which is gone has been
        // unsubscribed already, and nothing would ever take it out of an entity it entered after that.
        if (session.isClosed()) {
            return 0;
        }

        if (!onSessionEventLoop(session)) {
            final List<CharSequence> ids = new ArrayList<>(entityIds); // the caller may reuse its list
            session.executor().execute(
                    () -> doSubscribe(session, ids, new ArrayList<>(), knownOnly)
            );
            return 0;
        }

        // fails fast when the api was not built with SubscriptionWsApiBuilder: without the bookkeeping
        // of the session nothing would ever unsubscribe it
        final SubscriptionsWsApiObserver observer = getClientSessionSubscriptions(session).observer();

        int subscribed = 0;
        for (int i = 0; i < entityIds.size(); i++) {
            final CharSequence id = entityIds.get(i);

            final S subscriptions = knownOnly
                    ? getEntitySubscriptions(id) : getOrCreateEntitySubscriptions(id);

            if (subscriptions == null) {
                unknownEntityIds.add(id);
                if (observer != null) {
                    observer.onUnknownEntity(this, id);
                }
                continue;
            }

            subscriptions.add(session);
            subscribed++;
        }

        return subscribed;
    }

    public final int unsubscribe(final ClientSession session,
                                 final CharSequence entityId) {
        return unsubscribe(
                session,
                List.of(entityId),
                new ArrayList<>()
        );
    }

    /**
     * @see #subscribe(ClientSession, List, List) for the threading contract.
     *
     * @param session the session to apply the change to.
     * @param entityIds the ids to apply the change for.
     * @param notSubscribedEntityIds collects the ids the session was not subscribed to.
     * @return the number of the entities unsubscribed.
     */
    public final int unsubscribe(final ClientSession session,
                                 final List<CharSequence> entityIds,
                                 final List<CharSequence> notSubscribedEntityIds) {
        if (!onSessionEventLoop(session)) {
            final List<CharSequence> ids = new ArrayList<>(entityIds); // the caller may reuse its list
            session.executor().execute(
                    () -> unsubscribe(session, ids, new ArrayList<>())
            );
            return 0;
        }

        int unsubscribed = 0;
        for (int i = 0; i < entityIds.size(); i++) {
            final CharSequence id = entityIds.get(i);

            final S subscriptions = getEntitySubscriptions(id);
            if (subscriptions == null || !subscriptions.remove(session)) {
                notSubscribedEntityIds.add(id);
                continue;
            }

            unsubscribed++;
        }
        return unsubscribed;
    }

    /**
     * Unsubscribes the session from every entity of this channel. Runs inline on the event loop of the
     * session and is scheduled onto it from anywhere else - the same contract {@link #subscribe} and
     * {@link #unsubscribe} keep.
     *
     * <p>This walks the whole channel, so it is meant to be asked for. A session which goes away
     * unsubscribes itself through what it is actually subscribed to instead.
     *
     * @param session to unsubscribe.
     */
    public final void unsubscribeAll(final ClientSession session) {
        if (!onSessionEventLoop(session)) {
            session.executor().execute(() -> unsubscribeAll(session));
            return;
        }

        final ObjectListReadSafe.Snapshot<S> snapshot = entities.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final S subscriptions = snapshot.get(i);
            if (subscriptions == null) { // an entity which was removed, and left the slot behind
                continue;
            }
            try {
                subscriptions.remove(session);
            } catch (final Exception ignore) { // teardown: one entity whose application hook throws must
                // not leave the session subscribed to every entity after it in the list
            }
        }
    }

    public final S getOrCreateEntitySubscriptions(final CharSequence entityId) {
        final S existing = entitiesById.get(entityId); // the fast path takes no lock
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            if (closed.get()) { // we prevent creating new EntitySubscriptions after close
                throw new IllegalStateException("Channel closed");
            }

            S result = entitiesById.get(entityId);
            if (result != null) {
                return result;
            }

            final String sid = entityId.toString();
            result = newEntitySubscriptions(sid);
            if (result == null) {
                return null;
            }

            entities.add(result);
            entitiesById.put(sid, result); // findable only once it is walkable

            return result;
        }
    }

    public final S getEntitySubscriptions(final CharSequence entityId) {
        return entitiesById.get(entityId);
    }

    public final S removeEntitySubscriptions(final CharSequence entityId) {
        final S result;

        synchronized (this) {
            result = entitiesById.remove(entityId); // unfindable before it stops being walkable
            if (result == null) {
                return null;
            }

            entities.remove(result);
        }

        CloseHelper.closeQuiet(result);

        return result;
    }

    public final boolean isSubscribed(final ClientSession session) {
        final ObjectListReadSafe.Snapshot<S> snapshot = entities.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final S subscriptions = snapshot.get(i);
            if (subscriptions == null) {
                continue;
            }
            if (subscriptions.contains(session)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the entities of this channel, not the sessions subscribed to them. A consumer which throws
     * ends the walk and the exception is the caller's, unlike a fan-out over sessions: there is no session
     * here to report the failure against, and an entity which was skipped is a bug in the caller rather
     * than a peer which went away.
     *
     * @param consumer accepts the subscriptions of one entity.
     * @return the number of the entities walked.
     */
    public final int forEachSubscription(final Consumer<S> consumer) {
        final ObjectListReadSafe.Snapshot<S> snapshot = entities.snapshot();
        int walked = 0;
        for (int i = 0; i < snapshot.limit(); i++) {
            final S subscriptions = snapshot.get(i);
            if (subscriptions == null) {
                continue;
            }
            consumer.accept(subscriptions);
            walked++;
        }
        return walked;
    }

    public boolean isEmpty() {
        return entities.size() == 0;
    }

    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        final ObjectListReadSafe.Snapshot<S> gone;
        synchronized (this) {
            gone = entities.close();
            entitiesById.clear();
        }

        for (int i = 0; i < gone.limit(); i++) {
            final S subscriptions = gone.get(i);
            if (subscriptions == null) {
                continue;
            }
            CloseHelper.closeQuiet(subscriptions);
        }

        onClosed();
    }

    protected void onStarted() {
    }

    protected void onClosed() {
    }

    protected abstract S newEntitySubscriptions(String entityId);

}
