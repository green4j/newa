/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * What one session needs to exist: the channel, the receivers its frames go to, where its writes are
 * reported, and the two keep-alive numbers. Built by {@link WsApiHandler} for every handshake it completes,
 * or by hand for a session assembled outside an api.
 * <p>
 * A ping interval of 0 leaves a session unpinged and a read timeout of 0 never closes one for silence -
 * which is what a caller assembling a session by hand gets unless it asks otherwise.
 */
public class ClientSessionContext {
    private final WritingResult writingResult;
    private final Receiver.Text textReceiver;
    private final Receiver.Binary binaryReceiver;
    private final io.netty.channel.Channel channel;
    private final long pingIntervalMs;
    private final long readTimeoutMs;

    /**
     * A context without a read timeout. The keep-alive pair is decided by the api, and this constructor is
     * for a caller which is assembling a session by hand and wants no timers it did not ask for.
     *
     * @param writingResult told how every write of the session went.
     * @param textReceiver told about every text frame of the session, null to take no text - a text
     *                     frame is answered with a 1003 then.
     * @param binaryReceiver told about every binary frame of the session, null to take no binary - a
     *                       binary frame is answered with a 1003 then.
     * @param channel of the session.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never.
     */
    public ClientSessionContext(final WritingResult writingResult,
                                final Receiver.Text textReceiver,
                                final Receiver.Binary binaryReceiver,
                                final io.netty.channel.Channel channel,
                                final long pingIntervalMs) {
        this(
                writingResult,
                textReceiver,
                binaryReceiver,
                channel,
                pingIntervalMs,
                0
        );
    }

    /**
     * @param writingResult told how every write of the session went.
     * @param textReceiver told about every text frame of the session, null to take no text - a text
     *                     frame is answered with a 1003 then.
     * @param binaryReceiver told about every binary frame of the session, null to take no binary - a
     *                       binary frame is answered with a 1003 then.
     * @param channel of the session.
     * @param pingIntervalMs how often an idle session is pinged, 0 for never.
     * @param readTimeoutMs how long the peer may say nothing at all before the session is closed,
     *                      0 for as long as it likes.
     */
    public ClientSessionContext(final WritingResult writingResult,
                                final Receiver.Text textReceiver,
                                final Receiver.Binary binaryReceiver,
                                final io.netty.channel.Channel channel,
                                final long pingIntervalMs,
                                final long readTimeoutMs) {
        this.writingResult = writingResult;
        this.textReceiver = textReceiver;
        this.binaryReceiver = binaryReceiver;
        this.channel = channel;
        this.pingIntervalMs = pingIntervalMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public WritingResult writingResult() {
        return writingResult;
    }

    public Receiver.Text textReceiver() {
        return textReceiver;
    }

    public Receiver.Binary binaryReceiver() {
        return binaryReceiver;
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
