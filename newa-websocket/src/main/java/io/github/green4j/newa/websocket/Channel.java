package io.github.green4j.newa.websocket;


import io.github.green4j.newa.collections.CharSequenceToObjectMap;
import io.github.green4j.newa.collections.ObjectToObjectMap;

import java.util.ArrayList;
import java.util.List;

public abstract class Channel<S extends EntitySubscriptions> implements AutoCloseable {
    private final ObjectToObjectMap<ClientSession, ClientSessionSubscription> subscribersMap =
            new ObjectToObjectMap<>(); // guarded by itself

    private final List<ClientSession> allSubscribers = new ArrayList<>(); // guarded by subscribersMap

    private final CharSequenceToObjectMap<S> entitySubscriptionsMap
            = new CharSequenceToObjectMap<>(); // guarded by subscribersMap

    private volatile List<S> allSubscriptions = new ArrayList<>(); // can be accessed by
    // multiple threads

    private boolean started; // guarded by subscribersMap
    private boolean closed; // guarded by subscribersMap

    protected Channel() {
    }

    public void start() {
        synchronized (subscribersMap) {
            checkNotClosed();
            if (started) {
                throw new IllegalStateException("Already started.");
            }
            started = true;
        }
        doStart();
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void subscribe(final ClientSession session, final List<String> ids) {
        ClientSessionSubscription sessionSubscription;
        synchronized (subscribersMap) {
            checkStarted();

            sessionSubscription = subscribersMap.get(session, null);

            if (sessionSubscription == null) {
                sessionSubscription = new ClientSessionSubscription(session);
                subscribersMap.put(session, sessionSubscription);

                allSubscribers.add(session);
            }

            try {
                final int subscribed = subscribeEntities(session, ids);
                if (subscribed == 0) {
                    return;
                }
                sessionSubscription.numberOfSubscriptions += subscribed;
            } catch (final Exception e) {
                e.printStackTrace(); // TODO: logging
            }
        }
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void unsubscribe(final ClientSession session,
                                  final List<String> entities) {
        final ClientSessionSubscription sessionSubscription;
        synchronized (subscribersMap) {
            checkStarted();

            sessionSubscription = subscribersMap.get(session, null);
            if (sessionSubscription == null) {
                return;
            }

            try {
                final int unsubscribed = unsubscribeEntities(session, entities);
                if (unsubscribed == 0) {
                    return;
                }
                if ((sessionSubscription.numberOfSubscriptions -= unsubscribed) == 0) {
                    subscribersMap.remove(session);

                    allSubscribers.remove(session);
                }
            } catch (final Exception e) {
                e.printStackTrace(); // TODO: logging
            }
        }
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void unsubscribeAll(final ClientSession session) {
        synchronized (subscribersMap) {
            checkStarted();

            if (!subscribersMap.remove(session)) {
                return;
            }

            allSubscribers.remove(session);

            try {
                unsubscribeAllEntities(session);
            } catch (final Exception e) {
                e.printStackTrace(); // TODO: logging
            }
        }
    }

    @Override
    public void close() {
        synchronized (subscribersMap) {
            if (closed) {
                return;
            }

            closed = true;
        }
        doClose();
    }

    private void checkStarted() {
        checkNotClosed();
        if (!started) {
            throw new IllegalStateException("Not started yet.");
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Closed.");
        }
    }

    private int subscribeEntities(final ClientSession session, final List<String> entities) {
        assert Thread.holdsLock(subscribersMap);

        int result = 0;

        for (int i = 0; i < entities.size(); i++) {
            final String s = entities.get(i);

            S entitySubscriptions = entitySubscriptionsMap.get(s);
            if (entitySubscriptions == null) {
                entitySubscriptions = makeEntitySubscriptions(s);
                if (entitySubscriptions == null) {
                    continue; // unknown entity?
                }

                entitySubscriptionsMap.put(s, entitySubscriptions);
            }

            final List<S> newSnapshotEntitySubscriptions = new ArrayList<>(allSubscriptions);
            newSnapshotEntitySubscriptions.add(entitySubscriptions);
            allSubscriptions = newSnapshotEntitySubscriptions;

            if (entitySubscriptions.add(session)) {
                result++;
            }
        }

        return result;
    }

    private int unsubscribeEntities(final ClientSession session, final List<String> entities) {
        assert Thread.holdsLock(subscribersMap);

        int result = 0;

        for (int i = 0; i < entities.size(); i++) {
            final String e = entities.get(i);

            final EntitySubscriptions subscription = entitySubscriptionsMap.get(e);
            if (subscription == null) {
                continue; // unknown entity?
            }

            if (subscription.remove(session)) {
                result++;
            }
        }

        return result;
    }

    private void unsubscribeAllEntities(final ClientSession session) {
        assert Thread.holdsLock(subscribersMap);

        final List<S> currentSubscriptions = allSubscriptions;
        for (int i = 0; i < currentSubscriptions.size(); i++) {
            final S subscription = currentSubscriptions.get(i);
            subscription.remove(session);
        }
    }

    protected S getEntitySubscriptions(final CharSequence entity) {
        synchronized (subscribersMap) {
            return entitySubscriptionsMap.get(entity);
        }
    }

    protected List<S> getAllSubscriptions() {
        return allSubscriptions;
    }

    protected abstract S makeEntitySubscriptions(String entity);

    protected abstract void doStart();

    protected abstract void doClose();

    private static class ClientSessionSubscription {
        private final ClientSession session;
        private int numberOfSubscriptions = 0;

        ClientSessionSubscription(final ClientSession session) {
            this.session = session;
        }
    }
}
