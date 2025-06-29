package io.github.green4j.newa.websocket;

import io.github.green4j.newa.collections.CharSequenceToObjectMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Channel<S extends EntitySubscriptions> implements AutoCloseable {
    private static final List<String> EMPTY_LIST = Collections.emptyList();

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
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);

            final S subscriptions = getOrCreateEntitySubscriptions(id);
            subscriptions.add(session);
        }
    }

    public final List<String> subscribeKnown(final ClientSession session,
                                             final List<String> ids) {
        List<String> unknown = null;
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);

            final S subscriptions = getEntitySubscriptions(id);
            if (subscriptions == null) {
                if (unknown == null) {
                    unknown = new ArrayList<>();
                }
                unknown.add(id);
                continue; // unknown entity?
            }

            subscriptions.add(session);
        }
        return unknown == null ? EMPTY_LIST : unknown;
    }

    // subscribe and unsubscribe for one session must be called from one single thread
    // unsubscribeAll can be called from another thread
    public final void unsubscribe(final ClientSession session,
                                  final List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);

            final S subscriptions = getEntitySubscriptions(id);
            if (subscriptions == null) {
                continue; // unknown entity?
            }

            subscriptions.remove(session);
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

                final List<S> newEntitySubscriptions = new ArrayList<>(allSubscriptions);
                newEntitySubscriptions.add(result);
                allSubscriptions = newEntitySubscriptions;
            }
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
