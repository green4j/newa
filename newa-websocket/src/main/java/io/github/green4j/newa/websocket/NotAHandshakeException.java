/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * An HTTP request on the websocket's port which was not the handshake and which nothing else took. Reported
 * to the {@link io.github.green4j.newa.lang.ChannelErrorHandler} by {@link HandshakeOnlyHandler}, as the
 * connection is closed.
 * <p>
 * It carries a type of its own so that a handler can tell it from a failure and decide: a wrongly aimed
 * health check is worth a counter, a scanner is worth nothing at all, and neither is worth what a broken
 * server is worth.
 * <pre>{@code
 * WsServer.of(api).withChannelErrorHandler((channel, cause) -> {
 *     if (cause instanceof NotAHandshakeException) {
 *         wrongPort.increment();          // not a failure of this server
 *         return;
 *     }
 *     log.error("Channel {} failed", channel, cause);
 * });
 * }</pre>
 * It carries no stack trace: the frames would name Netty's decoders and say nothing about why the request
 * came. {@link #method()} and {@link #uri()} are what it is about, and both come <b>from the peer</b> - so
 * whatever writes them to a log is writing what somebody else chose.
 */
public class NotAHandshakeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String method;
    private final String uri;

    /**
     * @param method of the request, as it arrived.
     * @param uri    of the request, as it arrived.
     */
    public NotAHandshakeException(final String method,
                                  final String uri) {
        super("Not a handshake request: " + method + " " + uri, null, false, false);

        this.method = method;
        this.uri = uri;
    }

    /**
     * @return the method the peer asked with.
     */
    public String method() {
        return method;
    }

    /**
     * @return the uri the peer asked for, which is not the api's handshake path.
     */
    public String uri() {
        return uri;
    }
}
