package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.CloseHelper;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public class WsApi implements ClientSessionFactory, WritingResult {
    private final WsApiListener listener;
    private final String websocketPath;
    private final int pingIntervalMs;
    private final ClientSessions clientSessions;

    public WsApi(final WsApiListener listener,
                 final String websocketPath,
                 final int pingIntervalMs) {
        this.listener = listener;
        this.websocketPath = websocketPath;
        this.pingIntervalMs = pingIntervalMs;

        clientSessions = new ClientSessions(listener);
    }

    public String websocketPath() {
        return websocketPath;
    }

    public int pingIntervalMs() {
        return pingIntervalMs;
    }

    @Override
    public ClientSession newSession(final ClientSessionContext context) {
        return clientSessions.newSession(context);
    }

    public void broadcast(final CharSequence text) {
        clientSessions.broadcast(text);
    }

    public void broadcast(final CharSequence text,
                          final Charset charset) {
        clientSessions.broadcast(
                text,
                charset
        );
    }

    public void broadcast(final ByteBuf text) {
        clientSessions.broadcast(text);
    }

    @Override
    public void onWriteSuccess(final ClientSession session) {
    }

    @Override
    public void onWriteBackPressure(final ClientSession session) {
        if (session.isClosed()) {
            return;
        }

        try (session) {
            if (listener != null) {
                listener.onWriteBackPressure(session);
            }
        }
    }

    @Override
    public void onWriteError(final ClientSession session,
                             final Throwable error) {
        if (session.isClosed()) {
            return;
        }

        CloseHelper.closeQuiet(session);
    }
}
