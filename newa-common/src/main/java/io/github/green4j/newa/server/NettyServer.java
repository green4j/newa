/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.github.green4j.newa.lang.Ender;
import io.github.green4j.newa.lang.SelfEnding;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bound server and the two event loop groups under it, from {@link NettyServerBuilder#start()}.
 * <p>
 * It is an {@link AutoCloseable} which reports the end of its own listening channel, and nothing more: what
 * keeps a {@code main} alive until the server should stop, and what adds the JVM shutdown hook, is
 * {@link io.github.green4j.newa.lang.Life}:
 * <pre>{@code
 * new Life().run(() -> RestServer.start(api, 9009));
 * }</pre>
 * That separation is the point. Nothing here registers a shutdown hook of its own - a library which does
 * that behind your back is exactly what {@code Life} exists to keep out of the library - and an
 * {@link io.github.green4j.newa.lang.Ender} which can end a server has to exist before the server does,
 * which no method on this class ever could.
 */
public final class NettyServer implements SelfEnding {
    /**
     * The bound on every wait here. A net under a loop which will not stop rather than a working part: the
     * one wait which could never be satisfied - a caller closing from an event loop of this server - is
     * recognised instead of waited out.
     */
    private static final long CLOSE_TIMEOUT_MILLIS = 5_000L;

    /**
     * A response already on its way out is written by an event loop which is now being shut down, so the
     * loop is given a window in which a new task keeps it alive. Short enough to be imperceptible, long
     * enough that the ordering does not rest on the response happening to be small.
     */
    private static final long SHUTDOWN_QUIET_PERIOD_MILLIS = 100L;

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 2_000L;

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel channel;
    private final int port;
    private final ServerMemoryBudget.Registration memoryRegistration;

    private final AtomicBoolean closed = new AtomicBoolean();

    NettyServer(final EventLoopGroup bossGroup,
                final EventLoopGroup workerGroup,
                final Channel channel,
                final ServerMemoryBudget.Registration memoryRegistration) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.channel = channel;
        this.port = ((InetSocketAddress) channel.localAddress()).getPort();
        this.memoryRegistration = memoryRegistration;
        if (memoryRegistration != null) {
            channel.closeFuture().addListener(closed -> memoryRegistration.close());
        }
    }

    /**
     * @return the port actually bound, which is the only way to learn it after asking for port 0.
     */
    public int port() {
        return port;
    }

    /**
     * The listening channel, for what this handle deliberately does not wrap: the local address, the
     * allocator, a close listener of your own.
     *
     * @return the server channel, open until {@link #close()}.
     */
    public Channel channel() {
        return channel;
    }

    /**
     * The loops the accepted connections live on. Periodic work which touches sessions or channels belongs
     * here rather than on a thread of its own - a broadcast scheduled on this group runs where the sessions
     * it writes to already are.
     *
     * @return the worker group, alive until {@link #close()}.
     */
    public EventLoopGroup workerGroup() {
        return workerGroup;
    }

    /**
     * @return a read-only view of this server's memory registration, null when it was started without one
     */
    public ServerMemoryBudget.RegistrationSnapshot memoryRegistrationSnapshot() {
        return memoryRegistration == null ? null : memoryRegistration.snapshot();
    }

    /**
     * Tells the ender when this server's listening channel closes, which is the only end a server has: a
     * bound port lost under it looks like nothing at all from the outside, and a {@code main} waiting for
     * the end would go on waiting. {@link io.github.green4j.newa.lang.Life#run} registers itself here, so
     * under a {@link io.github.green4j.newa.lang.Life} there is nothing to write; a server run some other
     * way calls this with whatever ends that process.
     *
     * <p>It fires for {@link #close()} too, and that costs nothing: an {@link Ender} is idempotent, and the
     * cause of the end is whatever was given first.
     *
     * @param ender to tell. Told on the channel's event loop - so an {@link Ender} which closed this server
     *              there would be asking a loop to wait for its own shutdown, which is why {@link Ender}
     *              does no I/O.
     */
    @Override
    public void whenEnded(final Ender ender) {
        channel.closeFuture().addListener(closed -> ender.end("Port " + port + " closed"));
    }

    /**
     * Closes the listening channel and shuts both event loop groups down, giving whatever is still being
     * written a moment to drain first. A second call does nothing.
     *
     * <p>Call it from a thread which exists to wait - {@link io.github.green4j.newa.lang.Life#run} does.
     * Called from one of this server's own event loops it waits for nothing at all: a loop cannot confirm
     * the shutdown it is being asked to wait for while one of its own threads is the one waiting, so that
     * wait is not made. The close and both shutdowns are still asked for, and this returns before they have
     * finished - which is the only outcome there is on that thread.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final boolean waiting = !inOwnEventLoop();
        try {
            if (waiting) {
                channel.close().awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
            } else {
                channel.close();
            }
            shutdown(bossGroup, waiting);
            shutdown(workerGroup, waiting);
        } finally {
            if (memoryRegistration != null) {
                memoryRegistration.close();
            }
        }
    }

    /**
     * @return whether the calling thread is one this server would be waiting for.
     */
    private boolean inOwnEventLoop() {
        return inEventLoop(bossGroup) || inEventLoop(workerGroup);
    }

    private static boolean inEventLoop(final EventLoopGroup group) {
        // once per server, on the way out: the iterator this walks is not on anybody's hot path
        for (final EventExecutor executor : group) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    private static void shutdown(final EventLoopGroup group,
                                 final boolean waiting) {
        final Future<?> terminated = group.shutdownGracefully(
                SHUTDOWN_QUIET_PERIOD_MILLIS,
                SHUTDOWN_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
        );
        if (waiting) {
            terminated.awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
        }
    }
}
