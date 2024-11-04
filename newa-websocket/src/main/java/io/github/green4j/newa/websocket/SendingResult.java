package io.github.green4j.newa.websocket;

public interface SendingResult {

    void onSuccess(ClientSession session);

    void onBackPressure(ClientSession session);

    void onError(ClientSession session, Throwable error);
}