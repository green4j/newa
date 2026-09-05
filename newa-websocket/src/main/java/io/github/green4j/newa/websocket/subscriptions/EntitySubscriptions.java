/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.collections.ObjectListReadSafe;
import io.github.green4j.newa.websocket.ClientSession;
import io.netty.buffer.ByteBuf;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The client sessions subscribed to one entity, and the fan-out to them.
 * <p>
 * Reading it - {@link #publish(Consumer)}, {@link #contains(ClientSession)} - takes no lock and never makes
 * a writer wait, so a fan-out to a slow peer cannot stall a subscription, an unsubscription or an event
 * loop. Subscribing and unsubscribing take a short lock of this entity and copy nothing: a popular entity
 * thousands of clients arrive at costs what they are, not their square.
 * <p>
 * <b>Publishing.</b> Mutate the state of the entity first, then hand the fan-out to
 * {@link #publish(Consumer)} - or to {@link #publishTextAndRelease(ByteBuf)} when the frame is rendered once
 * for all the subscribers. That order is what the promise below rests on: {@code publish} numbers the
 * publication before it reads the subscribers, and that write is what makes the state mutation visible to a
 * session subscribing at the same moment. Publications of one entity have to be serialized by the
 * application - two concurrent publishers of one entity have no order between them, and nothing here can
 * invent one.
 * <p>
 * <b>Subscribing.</b> {@link #add(ClientSession)} is called on the event loop of the session being added,
 * which is also what makes two subscriptions of one session impossible.
 * {@link #onClientSessionSubscribed(ClientSession, long)} then runs inline, before {@code add} returns, so a
 * snapshot sent from it reaches the pipeline immediately; a concurrent publisher which has already seen the
 * session writes from another thread and is queued behind that task rather than overtaking it.
 * <p>
 * <b>What a subscriber is promised is no gaps and no reordering</b>: every publication is either included in
 * the snapshot or delivered after it. One may be seen twice - in the snapshot and again as an update - so
 * updates have to be idempotent, and {@code publicationSequence} is what tells them apart.
 * <p>
 * {@link #forEachSession(Consumer)} and its named twins are for what is not a state change - inspection, an
 * administrative frame - and number nothing, but carry the same barrier, so they cannot lose a session which
 * subscribes while they run.
 */
public class EntitySubscriptions implements Closeable {
    protected final String entityId;

    // Neither subscribing nor unsubscribing copies it, so a storm of clients arriving at a popular entity
    // costs what it is, not its square. A publication walks a snapshot of it by index, taking no lock and
    // allocating nothing.
    private final ObjectListReadSafe<ClientSession> subscribedSessions = new ObjectListReadSafe<>();

    private final AtomicLong publicationSequence = new AtomicLong();

    private final AtomicBoolean closed = new AtomicBoolean();

    public EntitySubscriptions(final String entityId) {
        this.entityId = entityId;
    }

    public final String entityId() {
        return entityId;
    }

    public final int numberOfSubscribedSessions() {
        return subscribedSessions.size();
    }

    public final boolean isEmpty() {
        return subscribedSessions.size() == 0;
    }

    /**
     * @return the sequence number of the last publication made, 0 if there was none yet.
     */
    public final long publicationSequence() {
        return publicationSequence.get();
    }

    /**
     * Must be called on the event loop of the session.
     *
     * <p>A closed session is not added. Everything which unsubscribes a session which goes away runs once,
     * inside {@link ClientSession#close()}, so a session added after that would stay here forever - and
     * that is exactly what a subscription {@link Channel} scheduled from another thread looks like when the
     * connection dropped before the scheduled task got its turn.
     *
     * @param session the session to subscribe.
     * @return true if the session has been added, false if it was subscribed already, if it is closed, or
     *         if this entity is closed.
     */
    public final boolean add(final ClientSession session) {
        if (session.isClosed()) { // the session is gone, and whatever unsubscribed it has run already,
            return false; // so nothing would ever take it out of here again
        }

        final ClientSessionSubscriptions subscriptions = ClientSessionSubscriptions.of(session);
        final SubscriptionsWsApiObserver observer = subscriptions != null ? subscriptions.observer() : null;

        // The registry of the session answers "subscribed already" without walking the subscribers of this
        // entity - a session holds a handful of subscriptions where a popular entity holds thousands of
        // sessions. Without a registry there is nothing to ask but the entity itself.
        final boolean subscribedAlready = subscriptions != null
                ? subscriptions.isSubscribedTo(this)
                : subscribedSessions.contains(session);

        if (subscribedAlready) {
            onClientSessionRepeatedSubscriptionTry(session);
            if (observer != null) {
                observer.onRepeatedSubscription(this);
            }
            return false;
        }

        if (!subscribedSessions.add(session)) { // this entity is closed: its close() has already reported
            return false; // the sessions it held, and this one never got in, so nothing is owed
        }

        if (subscriptions != null) {
            subscriptions.onSubscribed(this); // registered before the snapshot is built, so a session
            // going away while it is being built is still unsubscribed from here
        }

        // Load-bearing volatile read, not a leftover. A publication which did not deliver to this session
        // must have read the subscribers before the write which published this session, and it bumped the
        // sequence before that read. Therefore this read observes it, and the state mutation it published
        // becomes visible to the snapshot built below. Without this read there is no happens-before edge at
        // all and such a publication would be lost for this session forever.
        final long sequence = publicationSequence.get();

        onClientSessionSubscribed(session, sequence);
        if (observer != null) {
            observer.onSubscribed(this);
        }
        return true;
    }

    public final boolean contains(final ClientSession session) {
        return subscribedSessions.contains(session);
    }

    public final boolean remove(final ClientSession session) {
        final ClientSessionSubscriptions subscriptions = ClientSessionSubscriptions.of(session);
        final SubscriptionsWsApiObserver observer = subscriptions != null ? subscriptions.observer() : null;

        if (!subscribedSessions.remove(session)) {
            return false;
        }

        if (subscriptions != null) {
            subscriptions.onUnsubscribed(this);
        }

        onClientSessionUnsubscribed(session);
        if (observer != null) {
            observer.onUnsubscribed(this);
        }
        return true;
    }

    /**
     * Publishes an update of the entity to every subscribed session. The state of the entity
     * must be mutated <b>before</b> this call.
     *
     * @param consumer sends the update to a session.
     * @return the sequence number assigned to this publication.
     */
    public final long publish(final Consumer<ClientSession> consumer) {
        // The bump must precede the read of the subscribers below - it is the write
        // a concurrently subscribing session synchronizes with. See add().
        final long sequence = publicationSequence.incrementAndGet();

        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = subscribedSessions.snapshot();
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) { // a session which unsubscribed, and left the slot behind
                continue;
            }
            try {
                consumer.accept(session);
            } catch (final Exception cause) { // half a publication is not a state anything can recover
                session.deliveryFailed(cause); // from - the sequence is spent and the sessions already
                // reached have the update - so one session which throws costs that session, and the
                // fan-out goes on. Never throws, and never touches a buffer: whatever was handed to this
                // session was released by it, failure included
            }
        }

        return sequence;
    }

    /**
     * Publishes one already rendered text frame to every subscribed session and takes it over: each session
     * is given a retained duplicate of it, and the buffer itself is released once the fan-out is done. The
     * payload is rendered once instead of once per session, which for a publication reaching thousands of
     * subscribers is most of the work. The state of the entity must be mutated <b>before</b> this call,
     * the same way {@link #publish(Consumer)} requires.
     *
     * @param frame to send. Released here whatever happens to it.
     * @return the sequence number assigned to this publication.
     */
    public final long publishTextAndRelease(final ByteBuf frame) {
        try {
            return publish(
                    session -> session.sendText(frame.retainedDuplicate()) // a session consumes the frame
                    // it is given, so one buffer can not be handed to all of them
            );
        } finally {
            frame.release();
        }
    }

    /**
     * Publishes one already rendered text frame to every subscribed session and leaves it to the caller:
     * each session is given a retained duplicate of it, and the reference of the caller is neither taken
     * nor released, so the buffer can be sent again or kept. Use {@link #publishTextAndRelease(ByteBuf)}
     * when the frame was rendered for this publication and nothing else.
     *
     * @param frame to send. Stays the caller's to release.
     * @return the sequence number assigned to this publication.
     */
    public final long publishText(final ByteBuf frame) {
        return publishTextAndRelease(frame.retain()); // the reference taken below is the one added here,
        // so the caller keeps its own
    }

    /**
     * Publishes one already rendered binary frame to every subscribed session and takes it over, on the
     * terms {@link #publishTextAndRelease(ByteBuf)} sets out.
     *
     * @param frame to send. Released here whatever happens to it.
     * @return the sequence number assigned to this publication.
     */
    public final long publishBinaryAndRelease(final ByteBuf frame) {
        try {
            return publish(
                    session -> session.sendBinary(frame.retainedDuplicate())
            );
        } finally {
            frame.release();
        }
    }

    /**
     * Publishes one already rendered binary frame to every subscribed session and leaves it to the caller,
     * on the terms {@link #publishText(ByteBuf)} sets out.
     *
     * @param frame to send. Stays the caller's to release.
     * @return the sequence number assigned to this publication.
     */
    public final long publishBinary(final ByteBuf frame) {
        return publishBinaryAndRelease(frame.retain());
    }

    /**
     * Walks every subscribed session without numbering anything. Use it for inspection or
     * an administrative broadcast; a change of the state of the entity must go through
     * {@link #publish(Consumer)} instead, so that a subscriber can detect a hole by
     * the sequence number.
     *
     * @param consumer accepts a session.
     * @return the number of the sessions walked.
     */
    public final int forEachSession(final Consumer<ClientSession> consumer) {
        // The same barrier publish() relies on, without moving the counter. This read-modify-write
        // leaves the value alone but takes a position in the modification order of
        // publicationSequence - the very variable
        // a concurrently subscribing session reads in add(). Without it a session joining
        // right now could miss whatever this walk hands out. See add() for the full argument.
        publicationSequence.getAndAdd(0);

        final ObjectListReadSafe.Snapshot<ClientSession> snapshot = subscribedSessions.snapshot();
        int walked = 0;
        for (int i = 0; i < snapshot.limit(); i++) {
            final ClientSession session = snapshot.get(i);
            if (session == null) {
                continue;
            }
            try {
                consumer.accept(session);
            } catch (final Exception cause) {
                session.deliveryFailed(cause);
            }
            walked++;
        }
        return walked;
    }

    /**
     * Sends one already rendered text frame to every subscribed session without numbering anything, and
     * takes it over the way {@link #publishTextAndRelease(ByteBuf)} does: each session is given a retained
     * duplicate of it, and the buffer itself is released once the walk is done. Use it for an
     * administrative broadcast to the subscribers of this entity; a change of its state must go through
     * {@link #publishTextAndRelease(ByteBuf)} instead, so that a subscriber can detect a hole by the
     * sequence number.
     *
     * @param frame to send. Released here whatever happens to it.
     * @return the number of the sessions walked.
     */
    public final int forEachSessionTextAndRelease(final ByteBuf frame) {
        try {
            return forEachSession(
                    session -> session.sendText(frame.retainedDuplicate()) // a session consumes the frame
                    // it is given, so one buffer can not be handed to all of them
            );
        } finally {
            frame.release();
        }
    }

    /**
     * Sends one already rendered text frame to every subscribed session without numbering anything, and
     * leaves it to the caller the way {@link #publishText(ByteBuf)} does: each session is given a retained
     * duplicate of it, and the reference of the caller is neither taken nor released.
     *
     * @param frame to send. Stays the caller's to release.
     * @return the number of the sessions walked.
     */
    public final int forEachSessionText(final ByteBuf frame) {
        return forEachSessionTextAndRelease(frame.retain()); // the reference taken below is the one added
        // here, so the caller keeps its own
    }

    /**
     * Sends one already rendered binary frame to every subscribed session without numbering anything, and
     * takes it over, on the terms {@link #forEachSessionTextAndRelease(ByteBuf)} sets out.
     *
     * @param frame to send. Released here whatever happens to it.
     * @return the number of the sessions walked.
     */
    public final int forEachSessionBinaryAndRelease(final ByteBuf frame) {
        try {
            return forEachSession(
                    session -> session.sendBinary(frame.retainedDuplicate())
            );
        } finally {
            frame.release();
        }
    }

    /**
     * Sends one already rendered binary frame to every subscribed session without numbering anything, and
     * leaves it to the caller, on the terms {@link #forEachSessionText(ByteBuf)} sets out.
     *
     * @param frame to send. Stays the caller's to release.
     * @return the number of the sessions walked.
     */
    public final int forEachSessionBinary(final ByteBuf frame) {
        return forEachSessionBinaryAndRelease(frame.retain());
    }

    /**
     * Re-sends the snapshot to a session which had frames skipped while it could not keep up,
     * so that the skipped ones leave no hole in its stream. No barrier is needed here: the
     * session is already visible to the publishers, so every publication from now on reaches
     * it, and the snapshot only has to be no older than the frames which were skipped.
     *
     * <p>Called through the registry of the session, which is what says the session is subscribed -
     * asking the entity would mean walking its subscribers for every entity being re-synchronized.
     *
     * @param session the session to re-synchronize.
     */
    final void resync(final ClientSession session) {
        onClientSessionSubscribed(session, publicationSequence.get());
    }

    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        final ObjectListReadSafe.Snapshot<ClientSession> sessions = subscribedSessions.close();

        try {
            for (int i = 0; i < sessions.limit(); i++) {
                final ClientSession session = sessions.get(i);
                if (session == null) {
                    continue;
                }

                final ClientSessionSubscriptions subscriptions =
                        ClientSessionSubscriptions.of(session);
                if (subscriptions != null) {
                    subscriptions.onUnsubscribed(this); // the entity is gone, so no session may be left
                    // holding it - the one closing later would look for it in vain
                }

                try {
                    onClientSessionUnsubscribed(session);

                    final SubscriptionsWsApiObserver observer =
                            subscriptions != null ? subscriptions.observer() : null;
                    if (observer != null) {
                        observer.onUnsubscribed(this);
                    }
                } catch (final Exception ignore) { // this is teardown, and it is owed to every session
                    // the entity held: one of them throwing must not leave the rest of them still
                    // believing they are subscribed to an entity which is gone
                }
            }
        } finally {
            onClosed();
        }
    }

    /**
     * Invoked inline by {@link #add(ClientSession)}, on the event loop of the session, once
     * the session is visible to the publishers. This is the place to send an initial snapshot:
     * it is written before any concurrent update can reach the session.
     *
     * <p>Runs on the event loop, so building a heavy snapshot here delays the other sessions
     * sharing that loop.
     *
     * @param session the session just subscribed.
     * @param publicationSequence the sequence number the snapshot corresponds to. Every
     *                            publication with a greater number is delivered as an update.
     */
    protected void onClientSessionSubscribed(final ClientSession session,
                                             final long publicationSequence) {
    }

    protected void onClientSessionRepeatedSubscriptionTry(final ClientSession session) {
    }

    protected void onClientSessionUnsubscribed(final ClientSession session) {
    }

    protected void onClosed() {
    }
}
