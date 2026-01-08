package io.github.green4j.newa.websocket;

public class ClientSessionContext {
    private final WritingResult writingResult;
    private final Receiver receiver;
    private final io.netty.channel.Channel channel;
    private final long pingIntervalMs;

    public ClientSessionContext(final WritingResult writingResult,
                                final Receiver receiver,
                                final io.netty.channel.Channel channel,
                                final long pingIntervalMs) {
        this.writingResult = writingResult;
        this.receiver = receiver;
        this.channel = channel;
        this.pingIntervalMs = pingIntervalMs;
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
}
