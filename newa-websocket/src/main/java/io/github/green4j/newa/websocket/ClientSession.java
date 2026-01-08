package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.Cancelable;
import io.github.green4j.newa.lang.Executor;
import io.github.green4j.newa.lang.Scheduler;
import io.github.green4j.newa.lang.Sender;
import io.github.green4j.newa.lang.WallClock;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

public class ClientSession implements Sender, Closeable {
    private static final Charset DEFAULT_CHARSET = CharsetUtil.UTF_8;
    private static final ByteBuf PING_TEXT = Unpooled.copiedBuffer("ping", DEFAULT_CHARSET);

    private final long createTimeMs = WallClock.currentTimeMillis();

    private final ClientSessions owner;
    private final ClientSessionContext context;

    private final Cancelable pinger;

    private volatile long lastWriteTimeMs;
    private volatile long lastReadTimeMs;

    private volatile boolean closed;

    private volatile Object userData;

    ClientSession(final ClientSessions owner,
                  final ClientSessionContext context) {
        this.owner = owner;
        this.context = context;

        final long pingIntervalMs = context.pingIntervalMs();
        if (pingIntervalMs > 0) {
            pinger = scheduler().scheduleWithFixedDelay(
                    () -> {
                        final long now = WallClock.currentTimeMillis();
                        if (now - lastWriteTimeMs > pingIntervalMs) {
                            ping(PING_TEXT.retain());
                        }
                    },
                    pingIntervalMs,
                    pingIntervalMs
            );
        } else {
            pinger = null;
        }
    }

    public long createTimeMs() {
        return createTimeMs;
    }

    public long lastWriteTimeMs() {
        return lastWriteTimeMs;
    }

    public long lastReadTimeMs() {
        return lastReadTimeMs;
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData() {
        return (T) userData;
    }

    public synchronized <T> T putUserData(final T userData) {
        final T old = getUserData();
        this.userData = userData;
        return old;
    }

    public synchronized <T> T putUserDataIfAbsent(final T userData) {
        final T old = getUserData();
        if (old != null) {
            return old;
        }
        this.userData = userData;
        return userData;
    }

    public io.netty.channel.Channel channel() {
        return context.channel();
    }

    public void ping(final ByteBuf frame) {
        writeAndFlush(new PingWebSocketFrame(frame));
    }

    public void ping(final CharSequence frame,
                     final Charset charset) {
        ping(Unpooled.copiedBuffer(frame, charset));
    }

    public void ping(final CharSequence frame) {
        send(frame, CharsetUtil.UTF_8);
    }

    public void send(final ByteBuf frame) {
        writeAndFlush(new TextWebSocketFrame(frame));
    }

    public void send(final CharSequence frame,
                     final Charset charset) {
        send(Unpooled.copiedBuffer(frame, charset));
    }

    @Override
    public void send(final CharSequence frame) {
        send(frame, DEFAULT_CHARSET);
    }

    public void receive(final CharSequence frame) {
        lastReadTimeMs = WallClock.currentTimeMillis();

        final Receiver receiver = context.receiver();
        if (receiver == null) {
            return;
        }
        receiver.receive(this, frame);
    }

    public Executor executor() {
        return work -> context.channel().eventLoop().execute(work);
    }

    public Scheduler scheduler() {
        return (work, initialDelayMillis, delayMillis) -> {
            final ScheduledFuture<?> future = context.channel().eventLoop().scheduleWithFixedDelay(work,
                    initialDelayMillis,
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            return () -> future.cancel(true);
        };
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public final void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }

        try {
            if (pinger != null) {
                pinger.cancel();
            }

            final io.netty.channel.Channel c = context.channel();
            if (c.isOpen()) {
                c.close();
            }
        } finally {
            owner.onClientSessionClosed(this);
        }
    }

    private void writeAndFlush(final WebSocketFrame frame) {
        final io.netty.channel.Channel channel = context.channel();
        if (!channel.isWritable()) {
            if (!channel.isOpen()) {
                context.writingResult().onWriteError(this, new IOException("Channel closed"));
                return;
            }
            context.writingResult().onWriteBackPressure(this);
            return;
        }
        channel.writeAndFlush(frame);
        context.writingResult().onWriteSuccess(this); // A kind of optimistic result notification.
        // We do not check the real result of writeAndFlush() with a FutureListener
        // to leave things simple enough, so, detection of any problem is, in fact,
        // delayed until the next send() when the channel !isWritable()
        lastWriteTimeMs = WallClock.currentTimeMillis();
    }
}
