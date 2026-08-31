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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ClientSession implements Sender, Closeable {
    private static final Charset DEFAULT_CHARSET = CharsetUtil.UTF_8;
    private static final ByteBuf PING_TEXT = Unpooled.copiedBuffer("ping", DEFAULT_CHARSET);

    private final long createTimeMs = WallClock.currentTimeMillis();
    private final long createTimeNanos = System.nanoTime();

    private final ClientSessions owner;
    private final ClientSessionContext context;
    private final WsApiObserver observer; // null when the session is not observed

    private final Cancelable pinger;

    private volatile long lastWriteTimeMs;
    private volatile long lastReadTimeMs;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<Object> userData = new AtomicReference<>();

    private volatile boolean lagging; // set by a publisher when a frame is skipped,
    // cleared on the event loop once the channel is writable again

    ClientSession(final ClientSessions owner,
                  final ClientSessionContext context,
                  final WsApiObserver observer) {
        this.owner = owner;
        this.context = context;
        this.observer = observer;

        final long pingIntervalMs = context.pingIntervalMs();
        if (pingIntervalMs > 0) {
            pinger = scheduler().scheduleWithFixedDelay(
                    () -> {
                        if (!context.channel().isWritable()) {
                            return; // a channel with data still pending needs no keep-alive,
                            // and pinging it would only trip the back pressure handling
                        }
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

    /**
     * @return the observer of this session, null if it is not observed.
     */
    public WsApiObserver observer() {
        return observer;
    }

    public long lastWriteTimeMs() {
        return lastWriteTimeMs;
    }

    public long lastReadTimeMs() {
        return lastReadTimeMs;
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData() {
        return (T) userData.get();
    }

    @SuppressWarnings("unchecked")
    public <T> T putUserData(final T userData) {
        return (T) this.userData.getAndSet(userData);
    }

    @SuppressWarnings("unchecked")
    public <T> T putUserDataIfAbsent(final T userData) {
        final Object old = this.userData.compareAndExchange(null, userData);
        return old == null ? userData : (T) old;
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
        ping(frame, DEFAULT_CHARSET);
    }

    /**
     * Sends the buffer as one text frame and takes it over: it is released whatever happens to it - written,
     * skipped because the session can not keep up, or dropped because the channel is gone. Fanning one
     * buffer out to several sessions means a {@link ByteBuf#retainedDuplicate()} per session.
     *
     * @param frame to send.
     */
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

    void frameReceived(final int bytes) {
        if (observer != null) {
            observer.onFrameReceived(bytes);
        }
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
        return closed.get();
    }

    boolean clearLagging() { // called on the event loop only
        if (!lagging) {
            return false;
        }
        lagging = false;
        return true;
    }


    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
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
            owner.onClientSessionClosed(this); // reports the last of whatever the session was still
            // subscribed to, before the terminal event below

            if (observer != null) {
                observer.onSessionClosed(System.nanoTime() - createTimeNanos);
            }
        }
    }

    private void writeAndFlush(final WebSocketFrame frame) {
        final int bytes = frame.content().readableBytes(); // the frame belongs to the channel once
        // it is written, so its size is read here, while it is still ours

        final io.netty.channel.Channel channel = context.channel();
        if (!channel.isWritable()) {
            frame.release(); // it never reached the channel, so releasing it is ours: a frame built
            // from a pooled buffer, or from one shared by a fan-out, is leaked otherwise

            if (!channel.isOpen()) {
                context.writingResult().onWriteError(this, new IOException("Channel closed"));
                return;
            }
            lagging = true;
            if (observer != null) {
                observer.onWriteBackPressure(bytes);
            }
            context.writingResult().onWriteBackPressure(this);
            return;
        }
        channel.writeAndFlush(frame);
        if (observer != null) {
            observer.onFrameSent(bytes);
        }
        context.writingResult().onWriteSuccess(this); // A kind of optimistic result notification.
        // We do not check the real result of writeAndFlush() with a FutureListener
        // to leave things simple enough, so, detection of any problem is, in fact,
        // delayed until the next send() when the channel !isWritable()
        lastWriteTimeMs = WallClock.currentTimeMillis();
    }
}
