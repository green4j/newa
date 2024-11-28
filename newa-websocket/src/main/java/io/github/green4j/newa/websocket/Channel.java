package io.github.green4j.newa.websocket;

import io.github.green4j.newa.collections.CharSequenceToObjectMap;

import java.util.ArrayList;
import java.util.List;

public abstract class Channel<S extends EntitySubscriptions> implements AutoCloseable {
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
        doStart();
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void subscribe(final ClientSession session,
                                final List<String> ids) {
        try {
            for (int i = 0; i < ids.size(); i++) {
                final String id = ids.get(i);

                final S subscriptions = getOrCreateEntitySubscriptions(id);
                if (subscriptions == null) {
                    continue; // unknown entity?
                }

                subscriptions.add(session);
            }
        } catch (final Exception e) {
            e.printStackTrace(); // TODO: logging
        }
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void unsubscribe(final ClientSession session,
                                  final List<String> ids) {
        try {
            for (int i = 0; i < ids.size(); i++) {
                final String id = ids.get(i);

                final S subscriptions = getEntitySubscriptions(id);
                if (subscriptions == null) {
                    continue; // unknown entity?
                }

                subscriptions.remove(session);
            }
        } catch (final Exception e) {
            e.printStackTrace(); // TODO: logging
        }
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void unsubscribeAll(final ClientSession session) {
        session.executor().execute(() -> {
            final List<S> currentSubscriptions = allSubscriptions;
            for (int i = 0; i < currentSubscriptions.size(); i++) {
                final S subscriptions = currentSubscriptions.get(i);
                subscriptions.remove(session);
            }
        });
    }

    @Override
    public final void close() {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
        }
        doClose();
    }

    protected S getEntitySubscriptions(final CharSequence id) {
        synchronized (this) {
            return entitySubscriptionsMap.get(id);
        }
    }

    protected S getOrCreateEntitySubscriptions(final CharSequence id) {
        S result;

        synchronized (this) {
            result = entitySubscriptionsMap.get(id);
            if (result == null) {
                final String sid = id.toString();
                result = makeEntitySubscriptions(sid);
                if (result == null) {
                    return null;
                }

                entitySubscriptionsMap.put(sid, result);
            }

            final List<S> newEntitySubscriptions = new ArrayList<>(allSubscriptions);
            newEntitySubscriptions.add(result);
            allSubscriptions = newEntitySubscriptions;
        }

        return result;
    }

    protected List<S> getAllSubscriptions() {
        return allSubscriptions;
    }

    protected abstract void doStart();

    protected abstract S makeEntitySubscriptions(String id);

    protected abstract void doClose();
}
