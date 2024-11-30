package io.github.green4j.newa.websocket;

public interface WsApiServerListener extends ClientSessionsListener {

    void onBackPressure(ClientSession session);

}
