/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

/**
 * A handshake request refused by the {@link OriginPolicy}. Reported to the
 * {@link io.github.green4j.newa.lang.ChannelErrorHandler} by {@link OriginCheckHandler}, as the connection
 * is answered {@code 403} and closed.
 * <p>
 * It carries a type of its own so a handler can tell it from a failure - the same test
 * {@link NotAHandshakeException} shows - and it is worth more than a wrongly aimed health check: a page
 * somewhere asked this server for a session, in a browser willing to attach somebody's cookies to the
 * request, and the count of those is a thing to watch.
 * <p>
 * It carries no stack trace, since the frames would name Netty's decoders and nothing else.
 * {@link #origin()} comes <b>from the peer</b>, so whatever writes it to a log is writing what somebody else
 * chose.
 */
public class ForbiddenOriginException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String origin;

    /**
     * @param origin of the refused request, as it arrived, or null if it carried no such header.
     */
    public ForbiddenOriginException(final String origin) {
        super("Handshake refused, the origin is not allowed: " + origin, null, false, false);

        this.origin = origin;
    }

    /**
     * @return the origin the peer claimed, or null if it claimed none.
     */
    public String origin() {
        return origin;
    }
}
