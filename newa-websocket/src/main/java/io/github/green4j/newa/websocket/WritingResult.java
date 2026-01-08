package io.github.green4j.newa.websocket;

public interface WritingResult {

    void onWriteSuccess(ClientSession session);

    void onWriteBackPressure(ClientSession session);

    void onWriteError(ClientSession session, Throwable error);

}