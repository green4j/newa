package io.github.green4j.newa.websocket;

public interface Receiver {

    void receive(ClientSession session,
                 CharSequence message);

}
