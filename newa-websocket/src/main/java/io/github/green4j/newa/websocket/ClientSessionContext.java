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

public class ClientSessionContext {
    private final WritingResult writingResult;
    private final Receiver receiver;
    private final io.netty.channel.Channel channel;
    private final long pingIntervalMs;
    private final long readTimeoutMs;

    /**
     * A context without a read timeout. The keep-alive pair is decided by the api, and this constructor is
     * for a caller which is assembling a session by hand and wants no timers it did not ask for.
     *
     * @param writingResult told how every write of the session went.
     * @param receiver told about every data frame of the session, null to receive nothing -
     *                 an inbound frame is answered with a 1003 then.
     * @param channel of the session.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never.
     */
    public ClientSessionContext(final WritingResult writingResult,
                                final Receiver receiver,
                                final io.netty.channel.Channel channel,
                                final long pingIntervalMs) {
        this(
                writingResult,
                receiver,
                channel,
                pingIntervalMs,
                0
        );
    }

    /**
     * @param writingResult told how every write of the session went.
     * @param receiver told about every data frame of the session, null to receive nothing -
     *                 an inbound frame is answered with a 1003 then.
     * @param channel of the session.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never.
     * @param readTimeoutMs how long the peer may say nothing at all before the session is closed,
     *                      0 for as long as it likes.
     */
    public ClientSessionContext(final WritingResult writingResult,
                                final Receiver receiver,
                                final io.netty.channel.Channel channel,
                                final long pingIntervalMs,
                                final long readTimeoutMs) {
        this.writingResult = writingResult;
        this.receiver = receiver;
        this.channel = channel;
        this.pingIntervalMs = pingIntervalMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public WritingResult writingResult() {
        return writingResult;
    }

    public Receiver receiver() {
        return receiver;
    }

    public io.netty.channel.Channel channel() {
        return channel;
    }

    public long pingIntervalMs() {
        return pingIntervalMs;
    }

    public long readTimeoutMs() {
        return readTimeoutMs;
    }
}
