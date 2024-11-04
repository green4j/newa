package io.github.green4j.newa.websocket;

public interface Receiver {
    void receive(CharSequence message, ClientSession session);
}
