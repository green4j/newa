package io.github.green4j.newa.websocket;

public interface ClientSessionsListener {

    void onSessionOpened(ClientSession session);

    void onSessionClosed(ClientSession session);
}
