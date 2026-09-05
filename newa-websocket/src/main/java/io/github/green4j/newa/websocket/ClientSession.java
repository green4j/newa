/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
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

/**
 * One WebSocket connection, from the moment its handshake completed until its channel goes away. Everything
 * an application does to a peer it does through this: {@code sendText}, {@code sendBinary}, {@code ping},
 * {@code close}.
 * <p>
 * <b>Every send takes the buffer over.</b> A {@link ByteBuf} handed to one is released whatever becomes of
 * it - written, skipped because the session cannot keep up, or dropped because the channel is gone - so a
 * caller which wants to send the same bytes twice retains them itself. {@link #send(CharSequence)} is
 * {@link #sendText(CharSequence)} under the name {@link Sender} asks for.
 * <p>
 * Everything which touches a session has to end up on its event loop: {@link #executor()} hops back onto it
 * and {@link #scheduler()} repeats work on it, and neither may be blocked. {@link #channel()},
 * {@link #isClosed()}, {@link #createTimeMs()}, {@link #lastReadTimeMs()} and {@link #lastWriteTimeMs()}
 * answer from any thread.
 * <p>
 * {@link #close()} says nothing to the peer, which reads it as a {@code 1006} - indistinguishable from the
 * network dropping. {@link #closeWith(WebSocketCloseStatus)} says which close it is, and that is what a
 * client's reconnect is built on. Both are idempotent, and the first status given is the one sent.
 */
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
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(); // one status per session, whichever
    // caller got here first

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
            closeWith(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE); // nothing has come from the peer for
            return; // long enough to call it gone, and a peer which is merely silent - not gone, and
            // still reading - is told so rather than left to infer it from a disconnect. Checked
            // whatever the channel is doing: a peer which stopped reading is exactly the one whose
            // channel stopped being writable, it is no less gone for that, and closeWith closes it
            // where the status can not go out
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
    public void sendText(final ByteBuf frame) {
        writeAndFlush(new TextWebSocketFrame(frame));
    }

    public void sendText(final CharSequence frame,
                         final Charset charset) {
        sendText(Unpooled.copiedBuffer(frame, charset));
    }

    public void sendText(final CharSequence frame) {
        sendText(frame, DEFAULT_CHARSET);
    }

    /**
     * Sends the buffer as one binary frame and takes it over, on the same terms
     * {@link #sendText(ByteBuf)} does: it is released whatever happens to it, and one buffer fanned out to
     * several sessions means a {@link ByteBuf#retainedDuplicate()} per session.
     *
     * @param frame to send.
     */
    public void sendBinary(final ByteBuf frame) {
        writeAndFlush(new BinaryWebSocketFrame(frame));
    }

    @Override
    public void send(final CharSequence frame) {
        sendText(frame); // the name is the Sender interface's rather than this class's, and text is the
        // only thing a CharSequence can be sent as
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

    /**
     * Hands a whole text message to the {@link Receiver.Text} of this session.
     *
     * @param frame the text.
     */
    public void receive(final CharSequence frame) {
        receive(frame, true);
    }

    /**
     * Hands a text message, or one piece of it, to the {@link Receiver.Text} of this session, and answers
     * the peer itself when there is nobody to take it.
     *
     * @param frame the text, valid for the call and no longer as far as the receiver is concerned.
     * @param last whether the message ends here.
     */
    public void receive(final CharSequence frame,
                        final boolean last) {
        final Receiver.Text receiver = context.textReceiver();
        if (receiver == null) {
            closeWith(WebSocketCloseStatus.INVALID_MESSAGE_TYPE); // nothing here takes text, and a client
            // which sends it anyway is told which of the two it is rather than left with a frame which
            // went nowhere
            return;
        }
        try {
            receiver.text(this, frame, last);
        } catch (final Exception failed) {
            // the application failing to handle a frame is not the channel failing, and it is not the
            // decoder's business either: it is reported to this session's observer and ends this session
            receiveFailed(failed);
        }
    }

    /**
     * Hands a binary message, or one piece of it, to the {@link Receiver.Binary} of this session, on the
     * same terms {@link #receive(CharSequence, boolean)} does.
     *
     * @param frame the bytes, which the receiver must retain to keep.
     * @param last whether the message ends here.
     */
    public void receive(final ByteBuf frame,
                        final boolean last) {
        final Receiver.Binary receiver = context.binaryReceiver();
        if (receiver == null) {
            closeWith(WebSocketCloseStatus.INVALID_MESSAGE_TYPE);
            return;
        }
        try {
            receiver.binary(this, frame, last);
        } catch (final Exception failed) {
            receiveFailed(failed);
        }
    }

    /**
     * Reports a failure of the application handling a frame the way a failed write is reported - the
     * observer is told and the session is closed - and never throws itself. Called for anything thrown by
     * a {@link Receiver}, and public because a receiver which handles a frame somewhere else owes the
     * session the same treatment.
     * <p>
     * The session goes. A receiver which threw has said nothing about whether the state behind it is still
     * whole, and there is no frame this library could answer with in its place: the protocol on this
     * connection is the application's, not ours. What a client is told is a close of {@code 1011}, so that
     * it knows the server broke rather than that the connection merely went.
     *
     * @param cause of the failure.
     */
    public void receiveFailed(final Throwable cause) {
        try {
            if (observer != null) {
                observer.onReceiveFailed(cause);
            }
        } catch (final Exception ignore) {
            // an observer which throws does not get to keep the session: the failure it was being told
            // about is the last word on this connection either way
        } finally {
            closeWith(WebSocketCloseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Ends the session with a status the peer can read, rather than with a disconnect it can only guess at.
     * A bare {@link #close()} is a close of the connection and nothing else, which a client sees as
     * {@code 1006} and can not tell from the network going - and {@code 1006} is what a client backs off
     * from, where a {@code 1001} is what it reconnects to at once and a {@code 1008} is what it does not
     * reconnect from at all. Say which one it is whenever this end knows.
     * <p>
     * Never throws, and the session ends whatever happens to the status: the closing is what was asked
     * for, the saying why is the extra. The status goes out only over a channel which is open and
     * writable - a frame put into a buffer nobody is draining would hold the session open instead of
     * ending it - and the session closes once the frame has left, without waiting for the close the peer
     * answers with.
     *
     * @param status to close with. One of the codes an endpoint may send: {@code 1005}, {@code 1006} and
     *               {@code 1015} are statuses a peer infers and never receives, and asking for one of
     *               those closes the session with nothing said.
     */
    public void closeWith(final WebSocketCloseStatus status) {
        final io.netty.channel.Channel c = context.channel();
        if (closed.get() || !c.isOpen() || !c.isWritable()) {
            CloseHelper.closeQuiet(this); // idempotent, and there is nothing left to send the status on
            return;
        }
        if (!closeFrameSent.compareAndSet(false, true)) {
            return; // the status of whoever got here first is on its way, and the write which carries it
            // is what ends the session
        }

        final CloseWebSocketFrame frame;
        try {
            frame = new CloseWebSocketFrame(status);
        } catch (final IllegalArgumentException notSendable) {
            CloseHelper.closeQuiet(this); // asking for a status which may not be sent still asked for the
            // session to end
            return;
        }
        // the frame first and the close after it has gone out: closing the channel here would take the
        // status with it
        c.writeAndFlush(frame).addListener(written -> close());
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
