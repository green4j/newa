package io.github.green4j.newa.websocket;

public interface WsApiListener extends ClientSessionsListener {

    void onWriteBackPressure(ClientSession session);

}
