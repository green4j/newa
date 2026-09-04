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

package io.github.green4j.newa.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
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
 * Two numbers are deliberately left unset, because neither of them is this library's to guess: how many
 * connections the server will hold at once ({@link #maxConnections(int)}), and how deep the kernel's queue of
 * connections waiting to be accepted is ({@link #backlog(int)}). Without the first there is no limit, and
 * without the second the queue is as deep as the operating system says.
 */
public final class NettyServerBuilder {
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
    private String host;
    private Transport transport = Transport.auto();
    private int bossThreads = DEFAULT_BOSS_THREADS;
    private int workerThreads = DEFAULT_WORKER_THREADS;
    private int waterMarkLow = DEFAULT_WATER_MARK_LOW;
    private int waterMarkHigh = DEFAULT_WATER_MARK_HIGH;
    private int backlog;
    private int maxConnections;

    private ChannelInitializer<? extends Channel> childHandler;

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
     * The interface to bind. Every interface by default, which is what a server deployed anywhere needs and
     * what Netty's own {@code bind(int)} means; narrow it to {@code "127.0.0.1"} to keep a server to the
     * machine it runs on.
     *
     * @param host to bind, null for every interface.
     * @return this builder.
     */
    public NettyServerBuilder host(final String host) {
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
     * <p>What it defends is the file descriptor, and what it costs is that a refused peer is told nothing -
     * {@link ConnectionLimitHandler}, which this builds, says why, and counts what it refused.
     *
     * @param connections to hold at once, 0 for as many as the machine will give.
     * @return this builder.
     */
    public NettyServerBuilder maxConnections(final int connections) {
        this.maxConnections = connections;
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

        final EventLoopGroup bossGroup =
                new MultiThreadIoEventLoopGroup(bossThreads, transport.ioHandlerFactory());
        final EventLoopGroup workerGroup =
                new MultiThreadIoEventLoopGroup(workerThreads, transport.ioHandlerFactory());

        try {
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

            bootstrap.childHandler(limited(childHandler));

            final Channel channel = (host == null
                    ? bootstrap.bind(port)
                    : bootstrap.bind(InetAddress.getByName(host), port))
                    .sync()
                    .channel();

            return new NettyServer(bossGroup, workerGroup, channel);
        } catch (final InterruptedException interrupted) {
            shutdown(bossGroup, workerGroup);
            throw interrupted;
        } catch (final Exception failed) {
            shutdown(bossGroup, workerGroup);
            throw new IllegalStateException(
                    "Could not bind to " + (host == null ? "*" : host) + ":" + port, failed);
        }
    }

    /**
     * The child handler with the connection limit in front of it, when there is one. One
     * {@link ConnectionLimitHandler} serves the whole server - the count is what it holds - and the
     * initializer given here is added to the pipeline behind it, which is how a {@link ChannelInitializer}
     * composes: it runs and removes itself, leaving the handlers it added.
     *
     * @param handler of every accepted channel.
     * @return it, or an initializer which counts the connection first.
     */
    private ChannelInitializer<? extends Channel> limited(final ChannelInitializer<? extends Channel> handler) {
        if (maxConnections < 1) {
            return handler;
        }
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(maxConnections);
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                ch.pipeline().addLast(limit);
                ch.pipeline().addLast(handler);
            }
        };
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
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
