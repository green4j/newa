/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


package io.github.green4j.newa.websocket;

/**
 * A handshake request refused by the {@link OriginPolicy}. Reported to the
 * {@link io.github.green4j.newa.lang.ChannelErrorHandler} by {@link OriginCheckHandler}, as the connection
 * is answered {@code 403} and closed.
 * <p>
 * It carries a type of its own for the same reason {@link NotAHandshakeException} does: a refusal is not a
 * failure of this server, and what it is worth is not what a broken server is worth. It is, though, worth
 * more than a wrongly aimed health check - a page somewhere asked this server for a session in a browser
 * which was willing to attach somebody's cookies to the request, and the count of those is a thing to
 * watch.
 * <pre>{@code
 * WsServer.of(api).withChannelErrorHandler((channel, cause) -> {
 *     if (cause instanceof ForbiddenOriginException) {
 *         refusedOrigins.increment();      // not a failure of this server, but not nothing either
 *         return;
 *     }
 *     log.error("Channel {} failed", channel, cause);
 * });
 * }</pre>
 * It carries no stack trace: the frames would name Netty's decoders and say nothing about where the request
 * came from. {@link #origin()} is what it is about, and it comes <b>from the peer</b> - so whatever writes
 * it to a log is writing what somebody else chose.
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
