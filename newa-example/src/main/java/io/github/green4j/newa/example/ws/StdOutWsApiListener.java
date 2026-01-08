package io.github.green4j.newa.example.ws;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.WsApiListener;

public class StdOutWsApiListener implements WsApiListener {
    private final WsApiListener delegate;

    public StdOutWsApiListener() {
        this(null);
    }

    public StdOutWsApiListener(final WsApiListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
        System.out.printf("Slow consumer detected: %s%n", session.toString());

        if (delegate != null) {
            delegate.onWriteBackPressure(session);
        }
    }

    @Override
    public void onSessionOpened(final ClientSession session) {
        System.out.printf("A new session opened: %s%n", session.toString());

        if (delegate != null) {
            delegate.onSessionOpened(session);
        }
    }

    @Override
    public void onSessionClosed(final ClientSession session) {
        System.out.printf("The following session closed: %s%n", session.toString());

        if (delegate != null) {
            delegate.onSessionClosed(session);
        }
    }
}
