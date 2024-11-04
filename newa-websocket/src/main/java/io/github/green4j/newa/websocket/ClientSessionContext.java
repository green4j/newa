package io.github.green4j.newa.websocket;

public class ClientSessionContext {
    private final SendingResult sendingResult;
    private final Receiver receiver;
    private final io.netty.channel.Channel channel;

    public ClientSessionContext(final SendingResult sendingResult,
                                final Receiver receiver,
                                final io.netty.channel.Channel channel) {
        this.sendingResult = sendingResult;
        this.receiver = receiver;
        this.channel = channel;
    }

    public SendingResult sendingResult() {
        return sendingResult;
    }

    public Receiver receiver() {
        return receiver;
    }

    public io.netty.channel.Channel channel() {
        return channel;
    }
}
