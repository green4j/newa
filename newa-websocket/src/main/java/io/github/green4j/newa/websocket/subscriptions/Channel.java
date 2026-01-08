package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.collections.CharSequenceToObjectMap;
import io.github.green4j.newa.lang.CloseHelper;
import io.github.green4j.newa.websocket.ClientSession;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.github.green4j.newa.websocket.subscriptions.ChannelWsApiListener.getClientSessionSubscriptions;

public abstract class Channel<S extends EntitySubscriptions> implements Closeable {
    private final CharSequenceToObjectMap<S> entitySubscriptionsMap =
            new CharSequenceToObjectMap<>(); // guarded by this

    private volatile List<S> allSubscriptions = new ArrayList<>(); // can be accessed by
    // multiple threads

    private boolean started; // guarded by this
    private boolean closed; // guarded by this

    protected Channel() {
    }

    public final void start() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Closed");
            }

            if (started) {
                throw new IllegalStateException("Already started");
            }

            started = true;
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

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
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

    public final int subscribeForKnownOnly(final ClientSession session,
                                           final CharSequence entityId) {
        return subscribeForKnownOnly(
                session,
                List.of(entityId),
                new ArrayList<>()
        );
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final int subscribeForKnownOnly(final ClientSession session,
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
        int subscribed = 0;
        for (int i = 0; i < entityIds.size(); i++) {
            final CharSequence id = entityIds.get(i);

            final S subscriptions = knownOnly
                    ? getEntitySubscriptions(id) : getOrCreateEntitySubscriptions(id);

            if (subscriptions == null) {
                unknownEntityIds.add(id);
                continue;
            }

            subscriptions.add(session);
            subscribed++;
        }

        if (subscribed > 0) {
            final ClientSessionSubscriptions subscriptions = getClientSessionSubscriptions(session);
            subscriptions.onSubscribed(this);
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

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final int unsubscribe(final ClientSession session,
                                 final List<CharSequence> entityIds,
                                 final List<CharSequence> notSubscribedEntityIds) {
        int unsubscribed = 0;
        for (int i = 0; i < entityIds.size(); i++) {
            final CharSequence id = entityIds.get(i);

            final S subscriptions = getEntitySubscriptions(id);
            if (subscriptions == null) {
                notSubscribedEntityIds.add(id);
                continue;
            }

            subscriptions.remove(session);
            unsubscribed--;
        }
        return unsubscribed;
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread. So, we schedule its execution
    // on the same thread subscribe and unsubscribe are working in
    public final void unsubscribeAll(final ClientSession session) {
        session.executor().execute(() -> {
            final List<S> currentSubscriptions = allSubscriptions;
            for (int i = 0; i < currentSubscriptions.size(); i++) {
                final S subscriptions = currentSubscriptions.get(i);
                subscriptions.remove(session);
            }
        });
    }

    public final S getOrCreateEntitySubscriptions(final CharSequence entityId) {
        S result;

        synchronized (this) {
            result = entitySubscriptionsMap.get(entityId);
            if (result == null) {

                if (closed) { // we prevent creating new EntitySubscriptions after close
                    throw new IllegalStateException("Channel closed");
                }

                final String sid = entityId.toString();
                result = newEntitySubscriptions(sid);
                if (result == null) {
                    return null;
                }

                entitySubscriptionsMap.put(sid, result);

                final List<S> newEntitySubscriptions = new ArrayList<>(allSubscriptions);
                newEntitySubscriptions.add(result);
                allSubscriptions = newEntitySubscriptions;
            }
        }

        return result;
    }

    public final S getEntitySubscriptions(final CharSequence entityId) {
        synchronized (this) {
            return entitySubscriptionsMap.get(entityId);
        }
    }

    public final S removeEntitySubscriptions(final CharSequence entityId) {
        final S result;
        synchronized (this) {
            result = entitySubscriptionsMap.remove(entityId);
            if (result != null) {
                final List<S> newEntitySubscriptions = new ArrayList<>(allSubscriptions);
                newEntitySubscriptions.remove(result);
                allSubscriptions = newEntitySubscriptions;
            }
        }

        CloseHelper.closeQuiet(result);

        return result;
    }

    public final boolean isSubscribed(final ClientSession session) {
        final List<S> currentSubscriptions = allSubscriptions;
        for (int i = 0; i < currentSubscriptions.size(); i++) {
            final S subscriptions = currentSubscriptions.get(i);
            if (subscriptions.contains(session)) {
                return true;
            }
        }
        return false;
    }

    public final int forEachSubscription(final Consumer<S> consumer) {
        final List<S> currentSubscriptions = allSubscriptions;
        currentSubscriptions.forEach(consumer);
        return currentSubscriptions.size();
    }

    public boolean isEmpty() {
        return allSubscriptions.isEmpty();
    }

    @Override
    public final void close() {
        final List<S> currentSubscriptions;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;

            currentSubscriptions = allSubscriptions;

            entitySubscriptionsMap.clear();
            allSubscriptions = new ArrayList<>();
        }

        for (int i = 0; i < currentSubscriptions.size(); i++) {
            final S subscriptions = currentSubscriptions.get(i);
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
