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

import io.github.green4j.newa.lang.Cancelable;
import io.github.green4j.newa.lang.CloseHelper;
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
import io.netty.util.ReferenceCountUtil;
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

    private final Cancelable keepAlive; // null when neither a ping nor a read timeout was asked for

    private volatile long lastWriteTimeMs;
    // starts at the creation time rather than at zero: a session which has not read anything yet has
    // been silent for no time at all, and a read timeout comparing against zero would close it at once
    private volatile long lastReadTimeMs = createTimeMs;

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
        final long readTimeoutMs = context.readTimeoutMs();

        final long period = keepAlivePeriod(pingIntervalMs, readTimeoutMs);
        if (period > 0) {
            keepAlive = scheduler().scheduleWithFixedDelay(
                    () -> checkKeepAlive(pingIntervalMs, readTimeoutMs),
                    period,
                    period
            );
        } else {
            keepAlive = null;
        }
    }

    private static long keepAlivePeriod(final long pingIntervalMs,
                                        final long readTimeoutMs) {
        if (pingIntervalMs <= 0) {
            return Math.max(readTimeoutMs, 0);
        }
        if (readTimeoutMs <= 0) {
            return pingIntervalMs;
        }
        return Math.min(pingIntervalMs, readTimeoutMs); // whichever falls due first sets the resolution
    }

    private void checkKeepAlive(final long pingIntervalMs,
                                final long readTimeoutMs) {
        final long now = WallClock.currentTimeMillis();

        if (readTimeoutMs > 0 && now - lastReadTimeMs > readTimeoutMs) {
            CloseHelper.closeQuiet(this); // nothing has come from the peer for long enough to call it
            return; // gone. Checked whatever the channel is doing: a peer which stopped reading is
            // exactly the one whose channel stopped being writable, and it is no less gone for that
        }

        if (pingIntervalMs > 0
                && context.channel().isWritable() // a channel with data still pending needs no
                // keep-alive, and pinging it would only trip the back pressure handling
                && now - lastWriteTimeMs > pingIntervalMs) {
            ping(PING_TEXT.retain());
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

    void frameArrived() {
        lastReadTimeMs = WallClock.currentTimeMillis(); // any frame at all, because a pong answering our
        // ping is the only thing a session which does nothing but listen ever sends back
    }

    void frameReceived(final int bytes) {
        if (observer != null) {
            observer.onFrameReceived(bytes);
        }
    }

    public void receive(final CharSequence frame) {
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
            if (keepAlive != null) {
                keepAlive.cancel();
            }

            final io.netty.channel.Channel c = context.channel();
            if (c.isOpen()) {
                c.close();
            }
        } finally {
            try {
                owner.onClientSessionClosed(this); // reports the last of whatever the session was still
                // subscribed to, before the terminal event below
            } finally { // whatever the api makes of the session going away, the terminal event of the
                // observer is owed once per session and this is the only place which owes it
                if (observer != null) {
                    observer.onSessionClosed(System.nanoTime() - createTimeNanos);
                }
            }
        }
    }

    /**
     * Reports a failure the way a failed write is reported - the observer is told and the session is
     * closed - and never throws itself, so a fan-out which called into this session can go on to the next
     * one. Public because a fan-out of your own - over an {@code EntitySubscriptions.publish(Consumer)},
     * or over a list of sessions you keep yourself - owes the sessions it has not reached yet the same
     * treatment.
     *
     * @param cause of the failure.
     */
    public void deliveryFailed(final Throwable cause) {
        try {
            context.writingResult().onWriteError(this, cause);
        } catch (final Exception ignore) {
            CloseHelper.closeQuiet(this); // whatever the api makes of it, this session still goes
        }
    }

    private void writeAndFlush(final WebSocketFrame frame) {
        boolean ours = true; // the frame is ours to release until the channel takes it or it is released

        try {
            final int bytes = frame.content().readableBytes(); // the frame belongs to the channel once
            // it is written, so its size is read here, while it is still ours

            final io.netty.channel.Channel channel = context.channel();
            if (!channel.isWritable()) {
                frame.release(); // it never reached the channel, so releasing it is ours: a frame built
                // from a pooled buffer, or from one shared by a fan-out, is leaked otherwise
                ours = false;

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
            ours = false; // the channel owns it now, and releasing it below would be the second release
            if (observer != null) {
                observer.onFrameSent(bytes);
            }
            context.writingResult().onWriteSuccess(this); // A kind of optimistic result notification.
            // We do not check the real result of writeAndFlush() with a FutureListener
            // to leave things simple enough, so, detection of any problem is, in fact,
            // delayed until the next send() when the channel !isWritable()
            lastWriteTimeMs = WallClock.currentTimeMillis();
        } catch (final Exception cause) { // an observer, a writing result or an allocation may throw, and
            // a fan-out must lose this session rather than every session it had not reached yet. An Error
            // is left alone: it is not something to swallow once per session across a whole broadcast
            if (ours) {
                ReferenceCountUtil.safeRelease(frame);
            }
            deliveryFailed(cause);
        }
    }
}
