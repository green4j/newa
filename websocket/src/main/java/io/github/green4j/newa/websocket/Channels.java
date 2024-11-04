package io.github.green4j.newa.websocket;

public interface Channels extends Receiver, AutoCloseable {

    void start();

    void unsubscribeAll(ClientSession session);

    void close();
}
