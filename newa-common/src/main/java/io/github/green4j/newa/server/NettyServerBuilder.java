/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bootstrap under a server: the transport, the two event loop groups, the channel options, and the bind.
 * Everything above the socket - the codec, the api handler, whatever else - is what the child handler adds.
 * <pre>{@code
 * NettyServer server = new NettyServerBuilder()
 *         .port(9009)
 *         .workerThreads(4)
 *         .pipeline(pipeline -> pipeline.addLast(new HttpServerCodec()))
 *         .start();
 * }</pre>
 * The defaults are meant for a server that runs somewhere: the best transport this machine has, one boss
 * thread and a worker per core, {@code TCP_NODELAY}, and the write watermarks every backpressure policy in
 * this framework reads its signal from. Whatever is not named here is one {@link #option(ChannelOption,
 * Object)} or {@link #childOption(ChannelOption, Object)} away, and those are applied after the defaults, so
 * setting one of them again overrides it.
 * <p>
 * <b>The default interface is the loopback</b>, and that one is a security decision rather than a
 * convenience: a server which nobody opened up is a server no other machine can reach, so exposing one is a
 * line somebody has to write - {@link #host(String)} with the address to listen on, or with
 * {@link #ANY_HOST} for every interface.
 * <p>
 * Two numbers are deliberately left unset, because neither of them is this library's to guess: how many
 * connections the server will hold at once ({@link #maxConnections(int)}), and how deep the kernel's queue of
 * connections waiting to be accepted is ({@link #backlog(int)}). Without the first there is no limit, and
 * without the second the queue is as deep as the operating system says.
 */
public final class NettyServerBuilder {
    /**
     * The loopback, so a server binds nothing another machine can reach until it is told to. Netty's own
     * {@code bind(int)} means every interface; this one does not, because a default which exposes a service
     * is a default which exposes the one somebody forgot about.
     */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * What {@link #host(String)} is given to reach every interface - the word a deployment has to say out
     * loud, rather than a null or an empty string arriving from a configuration nobody filled in.
     * <p>
     * It binds the way Netty's {@code bind(int)} does, so it covers every address of every family this
     * machine has, which the literal {@code 0.0.0.0} resolved as an address would not.
     */
    public static final String ANY_HOST = "0.0.0.0";

    /**
     * A worker per core. The framework holds nothing per loop that a server has to pay for twice: routing
     * keeps a matcher per thread, and the counters shared across loops are atomics.
     */
    public static final int DEFAULT_WORKER_THREADS = Runtime.getRuntime().availableProcessors();

    /**
     * One listening socket needs one thread to accept on.
     */
    public static final int DEFAULT_BOSS_THREADS = 1;

    /**
     * Where a channel stops reporting itself writable. Small enough that a slow peer is noticed early,
     * large enough that an ordinary response never trips it.
     */
    public static final int DEFAULT_WATER_MARK_LOW = 32 * 1024;

    /**
     * @see #DEFAULT_WATER_MARK_LOW
     */
    public static final int DEFAULT_WATER_MARK_HIGH = 64 * 1024;

    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<>();
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<>();

    private int port;
    private String host = DEFAULT_HOST;
    private Transport transport = Transport.auto();
    private int bossThreads = DEFAULT_BOSS_THREADS;
    private int workerThreads = DEFAULT_WORKER_THREADS;
    private int waterMarkLow = DEFAULT_WATER_MARK_LOW;
    private int waterMarkHigh = DEFAULT_WATER_MARK_HIGH;
    private int backlog;
    private int minConnections;
    private int maxConnections;

    private ChannelInitializer<? extends Channel> childHandler;
    private ServerMemoryBudget memoryBudget;
    private ServerMemoryEstimate memoryEstimate;
    private String memoryBudgetName;
    private ConnectionObserver connectionObserver;

    public NettyServerBuilder() {
    }

    /**
     * @param port to listen on, or 0 to let the OS pick one - read it back from {@link NettyServer#port()}.
     * @return this builder.
     */
    public NettyServerBuilder port(final int port) {
        this.port = port;
        return this;
    }

    /**
     * The interface to bind, {@link #DEFAULT_HOST} by default - the loopback, which no other machine can
     * reach. A server which is meant to be reached says so here: the address of the one network it belongs
     * on, or {@link #ANY_HOST} for every interface.
     * <p>
     * A null is refused rather than read as either of those. Whichever of them it was taken to mean, the
     * server would be listening somewhere nobody chose, and one of the two answers is the whole machine.
     *
     * @param host to bind, or {@link #ANY_HOST} for every interface.
     * @return this builder.
     * @throws IllegalArgumentException if the host is null.
     */
    public NettyServerBuilder host(final String host) {
        if (host == null) {
            throw new IllegalArgumentException("A host is required: " + DEFAULT_HOST + " keeps a server to "
                    + "this machine, " + ANY_HOST + " opens it to every interface");
        }
        this.host = host;
        return this;
    }

    /**
     * @param transport to run on, {@link Transport#auto()} by default.
     * @return this builder.
     */
    public NettyServerBuilder transport(final Transport transport) {
        this.transport = transport;
        return this;
    }

    /**
     * @param threads accepting connections, {@link #DEFAULT_BOSS_THREADS} by default.
     * @return this builder.
     */
    public NettyServerBuilder bossThreads(final int threads) {
        this.bossThreads = threads;
        return this;
    }

    /**
     * @param threads serving accepted connections, {@link #DEFAULT_WORKER_THREADS} by default.
     * @return this builder.
     */
    public NettyServerBuilder workerThreads(final int threads) {
        this.workerThreads = threads;
        return this;
    }

    /**
     * @return the configured number of worker threads
     */
    public int workerThreads() {
        return workerThreads;
    }

    /**
     * Where a channel stops reporting itself writable, which is where a handler producing a large response,
     * a chunked one, or a fan-out reads its backpressure signal from.
     *
     * @param lowBytes the channel becomes writable again below.
     * @param highBytes the channel stops being writable above.
     * @return this builder.
     */
    public NettyServerBuilder writeBufferWaterMark(final int lowBytes,
                                                   final int highBytes) {
        this.waterMarkLow = lowBytes;
        this.waterMarkHigh = highBytes;
        return this;
    }

    /**
     * @return the configured low write-buffer watermark
     */
    public int writeBufferWaterMarkLow() {
        return effectiveWriteBufferWaterMark().low();
    }

    /**
     * @return the configured high write-buffer watermark
     */
    public int writeBufferWaterMarkHigh() {
        return effectiveWriteBufferWaterMark().high();
    }

    /**
     * How many connections the kernel may hold accepted, or half-accepted, before this server has taken them
     * - {@code SO_BACKLOG}, which is what decides whether a burst arriving faster than it is accepted waits
     * or is refused by the kernel outright.
     *
     * <p>Unset by default, which leaves Netty's own: whatever the operating system says its maximum is. That
     * is already the ceiling - a larger number is silently truncated to it - so this is the knob for asking
     * for <em>less</em> than the machine allows, or for saying the number out loud where a deployment
     * depends on it.
     *
     * @param connections the kernel may queue for this server to accept.
     * @return this builder.
     */
    public NettyServerBuilder backlog(final int connections) {
        this.backlog = connections;
        return this;
    }

    /**
     * Bounds how many connections this server holds at once: one arriving above the limit is closed as it
     * arrives, without a byte written back.
     *
     * <p>Unlimited by default, and that is a decision rather than an omission. The right number is what this
     * process may open minus everything else it holds open, which is a property of the deployment and not of
     * the code; a number picked here instead would cut working traffic quietly, which is worse than the
     * failure it prevents. Set it where that number is known.
     *
     * <p>What it defends is the file descriptor, and what it costs is that a refused peer is told nothing.
     * This side is told either way: without a memory budget {@link ConnectionLimitHandler} counts the
     * refusals and reports each to the {@link #connectionObserver(ConnectionObserver)}; with one, the
     * ceiling is enforced by the registration and reported to the budget's observer as
     * {@link ServerMemoryBudget.RefusalReason#CONNECTION_LIMIT}.
     *
     * @param connections to hold at once, 0 for as many as the machine will give.
     * @return this builder.
     */
    public NettyServerBuilder maxConnections(final int connections) {
        if (connections < 0) {
            throw new IllegalArgumentException(
                    "maxConnections must not be negative: " + connections);
        }
        this.maxConnections = connections;
        return this;
    }

    /**
     * @return the configured per-server connection ceiling, 0 when unset
     */
    public int maxConnections() {
        return maxConnections;
    }

    /**
     * Guarantees that this server can admit this many connections from its configured memory budget. Their
     * estimated heap and direct-memory cost is reserved when the server registers, so other servers cannot
     * consume it. The floor does not override {@link #maxConnections(int)}.
     *
     * <p>Unset by default. A positive floor requires {@link #memoryBudget(String, ServerMemoryBudget,
     * ServerMemoryEstimate)}; without a shared capacity to reserve it would provide no guarantee.
     *
     * @param connections to guarantee, 0 for no reserved floor
     * @return this builder
     */
    public NettyServerBuilder minConnections(final int connections) {
        if (connections < 0) {
            throw new IllegalArgumentException(
                    "minConnections must not be negative: " + connections);
        }
        this.minConnections = connections;
        return this;
    }

    /**
     * @return the configured per-server guaranteed connection floor, 0 when unset
     */
    public int minConnections() {
        return minConnections;
    }

    /**
     * Admits this server's connections against a process-wide memory budget. The estimate is accounting, not
     * an allocator limit: the server which derived it is responsible for keeping it representative of the
     * configuration it starts with. {@link #minConnections(int)} and {@link #maxConnections(int)} are
     * optional per-server bounds on that admission.
     *
     * @param name identifying this server in budget statistics
     * @param budget shared with every server drawing from the same heap and direct-memory limits
     * @param estimate reserved by each accepted connection
     * @return this builder
     */
    public NettyServerBuilder memoryBudget(final String name,
                                           final ServerMemoryBudget budget,
                                           final ServerMemoryEstimate estimate) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A server memory budget registration requires a name");
        }
        if (budget == null) {
            throw new IllegalArgumentException("A server memory budget is required");
        }
        if (estimate == null) {
            throw new IllegalArgumentException("A server memory estimate is required");
        }
        this.memoryBudgetName = name;
        this.memoryBudget = budget;
        this.memoryEstimate = estimate;
        return this;
    }

    /**
     * Sets where a connection refused by {@link #maxConnections(int)} is reported. Nothing is reported
     * without one, and a refusal leaves no other trace.
     *
     * <p>The servers of the modules above set this from their own {@code withConnectionObserver}, so one
     * observer covers the limit here and their deadlines too.
     *
     * @param observer told about refused connections, null to say nothing. One serves the whole server.
     * @return this builder.
     */
    public NettyServerBuilder connectionObserver(final ConnectionObserver observer) {
        this.connectionObserver = observer;
        return this;
    }

    /**
     * @param <T> type of the option's value.
     * @param option of the listening channel.
     * @param value to set it to.
     * @return this builder.
     */
    public <T> NettyServerBuilder option(final ChannelOption<T> option,
                                         final T value) {
        options.put(option, value);
        return this;
    }

    /**
     * @param <T> type of the option's value.
     * @param option of every accepted channel.
     * @param value to set it to.
     * @return this builder.
     */
    public <T> NettyServerBuilder childOption(final ChannelOption<T> option,
                                              final T value) {
        childOptions.put(option, value);
        return this;
    }

    /**
     * The pipeline of every accepted channel. This is what {@code RestServer.pipeline()} and
     * {@code WsServer.pipeline()} hand over.
     *
     * @param initializer building the pipeline of one channel.
     * @return this builder.
     */
    public NettyServerBuilder childHandler(final ChannelInitializer<? extends Channel> initializer) {
        this.childHandler = initializer;
        return this;
    }

    /**
     * The same slot as {@link #childHandler(ChannelInitializer)}, without the anonymous class - the form for
     * a pipeline written out by hand. Called once per accepted channel, which is why a handler that is not
     * {@code @Sharable} is constructed inside it rather than captured.
     *
     * @param init adding the handlers of one channel.
     * @return this builder.
     */
    public NettyServerBuilder pipeline(final Consumer<ChannelPipeline> init) {
        return childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                init.accept(ch.pipeline());
            }
        });
    }

    /**
     * Binds. Both event loop groups are shut down if the bind fails, so a caught failure leaves no threads
     * behind - they are not daemons, and a JVM which cannot exit is the usual way that is noticed.
     *
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start() throws InterruptedException {
        if (childHandler == null) {
            throw new IllegalStateException(
                    "No child handler: nothing would serve the connections this server accepts");
        }
        if (minConnections > 0 && memoryBudget == null) {
            throw new IllegalStateException(
                    "minConnections requires a memory budget");
        }

        final ServerMemoryBudget.Registration memoryRegistration = memoryBudget == null
                ? null
                : memoryBudget.register(
                        memoryBudgetName,
                        memoryEstimate,
                        minConnections,
                        maxConnections
                );
        EventLoopGroup bossGroup = null;
        EventLoopGroup workerGroup = null;

        try {
            bossGroup = new MultiThreadIoEventLoopGroup(bossThreads, transport.ioHandlerFactory());
            workerGroup = new MultiThreadIoEventLoopGroup(workerThreads, transport.ioHandlerFactory());

            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(transport.serverSocketChannel())
                    .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(waterMarkLow, waterMarkHigh));

            if (backlog > 0) {
                bootstrap.option(ChannelOption.SO_BACKLOG, backlog);
            }

            // after the defaults, so asking for one of them again is how it is overridden
            applyOptions(bootstrap, false);
            applyOptions(bootstrap, true);

            bootstrap.childHandler(limited(childHandler, memoryRegistration));

            // ANY_HOST goes through bind(port) rather than through an address of that name: the wildcard
            // Netty binds there covers every family this machine has, where 0.0.0.0 resolved is IPv4 alone
            final Channel channel = (ANY_HOST.equals(host)
                    ? bootstrap.bind(port)
                    : bootstrap.bind(InetAddress.getByName(host), port))
                    .sync()
                    .channel();

            return new NettyServer(bossGroup, workerGroup, channel, memoryRegistration);
        } catch (final InterruptedException interrupted) {
            shutdown(bossGroup, workerGroup);
            close(memoryRegistration);
            throw interrupted;
        } catch (final Exception failed) {
            shutdown(bossGroup, workerGroup);
            close(memoryRegistration);
            throw new IllegalStateException(
                    "Could not bind to " + host + ":" + port, failed);
        }
    }

    /**
     * The child handler with the connection limit in front of it, when there is one. One
     * {@link ConnectionLimitHandler} serves the whole server - the count is what it holds - and the
     * initializer given here is added to the pipeline behind it, which is how a {@link ChannelInitializer}
     * composes: it runs and removes itself, leaving the handlers it added.
     *
     * @param handler of every accepted channel.
     * @param memoryRegistration process-wide admission, null when it is not configured
     * @return it, or an initializer which counts the connection first.
     */
    private ChannelInitializer<? extends Channel> limited(
            final ChannelInitializer<? extends Channel> handler,
            final ServerMemoryBudget.Registration memoryRegistration) {
        if (memoryRegistration == null && maxConnections < 1) {
            return handler;
        }
        final ChannelHandler limit = memoryRegistration == null
                ? new ConnectionLimitHandler(maxConnections, connectionObserver) : null;
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                if (memoryRegistration != null) {
                    final ServerMemoryBudget.Lease lease = memoryRegistration.tryAcquire();
                    if (lease == null) {
                        ch.close();
                        return;
                    }
                    ch.closeFuture().addListener(closed -> lease.close());
                } else {
                    ch.pipeline().addLast(limit);
                }
                ch.pipeline().addLast(handler);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private WriteBufferWaterMark effectiveWriteBufferWaterMark() {
        int low = waterMarkLow;
        int high = waterMarkHigh;
        for (final Map.Entry<ChannelOption<?>, Object> entry : childOptions.entrySet()) {
            final ChannelOption<?> option = entry.getKey();
            if (option.equals(ChannelOption.WRITE_BUFFER_WATER_MARK)) {
                final WriteBufferWaterMark waterMark =
                        (WriteBufferWaterMark) entry.getValue();
                low = waterMark.low();
                high = waterMark.high();
            } else if (option.equals(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK)) {
                low = (Integer) entry.getValue();
            } else if (option.equals(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK)) {
                high = (Integer) entry.getValue();
            }
        }
        return new WriteBufferWaterMark(low, high);
    }

    @SuppressWarnings("unchecked") // the maps are only ever filled through option()/childOption(), which
    // pair every ChannelOption<T> with a T
    private void applyOptions(final ServerBootstrap bootstrap,
                              final boolean child) {
        final Map<ChannelOption<?>, Object> toApply = child ? childOptions : options;
        for (final Map.Entry<ChannelOption<?>, Object> entry : toApply.entrySet()) {
            final ChannelOption<Object> option = (ChannelOption<Object>) entry.getKey();
            if (child) {
                bootstrap.childOption(option, entry.getValue());
            } else {
                bootstrap.option(option, entry.getValue());
            }
        }
    }

    private static void shutdown(final EventLoopGroup bossGroup,
                                 final EventLoopGroup workerGroup) {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private static void close(final ServerMemoryBudget.Registration registration) {
        if (registration != null) {
            registration.close();
        }
    }
}
