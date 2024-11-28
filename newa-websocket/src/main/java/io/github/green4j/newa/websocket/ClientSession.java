package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.Executor;
import io.github.green4j.newa.lang.Sender;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.Closeable;
import java.io.IOException;

public class ClientSession implements Sender, Closeable {

    private final ClientSessions owner;
    private final ClientSessionContext context;

    private volatile boolean closed;

    public ClientSession(final ClientSessions owner,
                         final ClientSessionContext context) {
        this.owner = owner;
        this.context = context;
    }

    @Override
    public void send(final CharSequence frame) {
        final io.netty.channel.Channel channel = context.channel();
        if (!channel.isWritable()) {
            if (!channel.isOpen()) {
                context.sendingResult().onError(this, new IOException("Channel closed"));
                return;
            }
            context.sendingResult().onBackPressure(this);
            return;
        }
        channel.writeAndFlush(new TextWebSocketFrame(frame.toString())); // TODO: optimize for GC?
        context.sendingResult().onSuccess(this); // A kind of optimistic result notification.
        // We do not check the real result of writeAndFlush() with a FutureListener
        // to leave things simple enough, so, detection of any problem is, in fact,
        // delayed until the next send() when the channel !isWritable()
    }

    public void receive(final CharSequence frame) {
        context.receiver().receive(frame, this);
    }

    public Executor executor() {
        return work -> context.channel().eventLoop().execute(work);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public final void close() {
        try {
            final io.netty.channel.Channel c = context.channel();
            if (c.isOpen()) {
                c.close();
            }
        } finally {
            closed = true;

            owner.closeClientSession(this);
        }
    }
}
