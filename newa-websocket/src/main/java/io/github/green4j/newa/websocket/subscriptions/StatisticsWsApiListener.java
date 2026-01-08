package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.WsApiListener;

import java.util.concurrent.atomic.AtomicLong;

public class StatisticsWsApiListener implements WsApiListener {
    private final WsApiListener delegate;

    private final AtomicLong sessionsOpened = new AtomicLong();
    private final AtomicLong sessionClosed = new AtomicLong();
    private final AtomicLong slowConsumerEvents = new AtomicLong();

    public StatisticsWsApiListener() {
        this(null);
    }

    public StatisticsWsApiListener(final WsApiListener delegate) {
        this.delegate = delegate;
    }

    public long sessionsOpened() {
        return sessionsOpened.get();
    }

    public long sessionClosed() {
        return sessionClosed.get();
    }

    public long slowConsumerEvents() {
        return slowConsumerEvents.get();
    }

    @Override
    public void onSessionOpened(final ClientSession session) {
        sessionsOpened.incrementAndGet();

        if (delegate != null) {
            delegate.onSessionOpened(session);
        }
    }

    @Override
    public void onSessionClosed(final ClientSession session) {
        sessionClosed.incrementAndGet();

        if (delegate != null) {
            delegate.onSessionClosed(session);
        }
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
        if (!session.isClosed()) {
            slowConsumerEvents.incrementAndGet();
        }

        if (delegate != null) {
            delegate.onWriteBackPressure(session);
        }
    }
}
