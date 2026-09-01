package io.github.green4j.newa.performance;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The load client both servers are measured with.
 * <p>
 * A <i>client</i> here is a keep-alive connection carrying exactly one request at a time - no pipelining -
 * and there are as many of them as the run asks for. They are driven from a handful of event loops, so a
 * thousand clients cost a thousand sockets and not a thousand threads, and the client keeps to its half of
 * the machine whatever the run's shape.
 * <p>
 * In {@link Mode#THROUGHPUT} a connection sends its next request the moment the previous response arrives,
 * so the offered load is exactly what the server can absorb, and the event loops do nothing but I/O.
 * <p>
 * In {@link Mode#LATENCY} requests are issued on a schedule instead, and are timed from the instant each was
 * <i>due</i> rather than the instant it actually went out - without that, a server which stalls simply stops
 * being asked, and the stall never appears in its own percentiles. The schedule is kept by a thread of its
 * own, spinning; it must not be an event loop task, because a task which is always ready leaves the loop it
 * runs on no time to read the responses it is waiting for, and the run then measures the client.
 */
public final class LoadClient implements AutoCloseable {
    /**
     * How many distinct paths the client rotates through. The server renders a different document for each,
     * so nothing it does can be hoisted out of the measurement, and the client still builds no strings.
     */
    private static final int URI_RING = 1024;

    private static final long LOWEST_TRACKED_NANOS = 1000L;
    private static final long HIGHEST_TRACKED_NANOS = 60L * 1000 * 1000 * 1000;
    private static final int SIGNIFICANT_DIGITS = 3;

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final Mode mode;
    private final String host;
    private final int port;
    private final int clients;
    private final long periodNanos;
    private final String[] uris;

    private final MultiThreadIoEventLoopGroup group;
    private final Loop[] loops;
    private final List<Connection> connections = new ArrayList<>();

    /**
     * Connections with nothing in flight, waiting for the pacer to give them their next due time. Only
     * {@link Mode#LATENCY} uses it - in the other mode a connection which is free is already sending.
     */
    private final Queue<Connection> idle = new ConcurrentLinkedQueue<>();

    private Pacer pacer;

    public LoadClient(final Mode mode,
                      final String host,
                      final int port,
                      final int clients,
                      final long rate,
                      final String pathPrefix) {
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.clients = clients;
        this.periodNanos = Math.max(1L, 1_000_000_000L / rate);

        uris = new String[URI_RING];
        for (int i = 0; i < URI_RING; i++) {
            uris[i] = pathPrefix + i;
        }

        // in latency mode one of the client's threads keeps the schedule instead of doing I/O, so the client
        // still occupies exactly the half of the machine the core split gave it
        final int available = mode == Mode.LATENCY
                ? Math.max(1, Cores.clientThreads() - 1)
                : Cores.clientThreads();
        final int threads = Math.min(available, clients);
        group = new MultiThreadIoEventLoopGroup(threads, Transport.ioHandlerFactory());

        loops = new Loop[threads];
        int index = 0;
        for (final EventExecutor executor : group) {
            loops[index] = new Loop(index, (EventLoop) executor);
            index++;
        }
    }

    /**
     * Connects every client, runs the load for the warmup, and resets the counters. Split from
     * {@link #measure(int)} so that a caller can sample the server's own statistics either side of the
     * measured window and not of the warmup.
     *
     * @param warmupSeconds to run before the counters are reset
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void startAndWarmUp(final int warmupSeconds) throws InterruptedException {
        connect();
        start();
        if (warmupSeconds > 0) {
            TimeUnit.SECONDS.sleep(warmupSeconds);
            reset();
        }
    }

    /**
     * Measures, then stops sending. {@link #close()} gives the threads and sockets back.
     *
     * @param durationSeconds to measure over
     * @return what the measured window produced
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public LoadResult measure(final int durationSeconds) throws InterruptedException {
        final long startNanos = System.nanoTime();
        TimeUnit.SECONDS.sleep(durationSeconds);
        final long elapsedNanos = System.nanoTime() - startNanos;
        return stop(elapsedNanos);
    }

    /**
     * Warms up and measures in one call.
     *
     * @param warmupSeconds to run before the counters are reset
     * @param durationSeconds to measure over
     * @return what the measured window produced
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public LoadResult run(final int warmupSeconds,
                          final int durationSeconds) throws InterruptedException {
        startAndWarmUp(warmupSeconds);
        return measure(durationSeconds);
    }

    private void connect() throws InterruptedException {
        final List<ChannelFuture> futures = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            final Connection connection = new Connection(loops[i % loops.length]);
            connections.add(connection);
            futures.add(connection.connect());
        }
        for (int i = 0; i < futures.size(); i++) {
            final ChannelFuture future = futures.get(i).await();
            if (!future.isSuccess()) {
                throw new IllegalStateException("Could not connect client " + i
                        + " to " + host + ":" + port, future.cause());
            }
        }
    }

    private void start() {
        for (final Loop loop : loops) {
            loop.running = true;
        }
        if (mode == Mode.THROUGHPUT) {
            for (int i = 0; i < connections.size(); i++) {
                final Connection connection = connections.get(i);
                connection.loop.eventLoop.execute(() -> connection.send(0L));
            }
            return;
        }
        pacer = new Pacer();
        pacer.start();
    }

    private void reset() {
        awaitAll(loop -> loop.eventLoop.submit(loop::reset));
        if (pacer != null) {
            pacer.reset();
        }
    }

    /**
     * Stops the loops and collects what they counted. The counting is done <i>on</i> each loop, in the same
     * task which stops it: responses still arriving while the run is being wound up would otherwise be seen
     * half-counted from here, with a request added to one total and its latency missing from another.
     *
     * @param elapsedNanos the measured window lasted
     * @return what every loop counted, added up
     */
    private LoadResult stop(final long elapsedNanos) {
        final long peakBacklog;
        if (pacer != null) {
            peakBacklog = pacer.stopAndJoin();
        } else {
            peakBacklog = 0;
        }

        final Snapshot[] snapshots = new Snapshot[loops.length];
        awaitAll(loop -> loop.eventLoop.submit(() -> snapshots[loop.index] = loop.stopAndSnapshot()));

        long requests = 0;
        long bytes = 0;
        long badStatuses = 0;
        long ioErrors = 0;
        long reconnects = 0;
        final Histogram latencies = mode == Mode.LATENCY ? newHistogram() : null;

        for (int i = 0; i < snapshots.length; i++) {
            final Snapshot snapshot = snapshots[i];
            requests += snapshot.requests;
            bytes += snapshot.bytes;
            badStatuses += snapshot.badStatuses;
            ioErrors += snapshot.ioErrors;
            reconnects += snapshot.reconnects;
            if (latencies != null) {
                latencies.add(snapshot.latencies);
            }
        }
        return new LoadResult(mode, clients, requests, bytes, 0L, 0L, 0L, badStatuses, ioErrors,
                reconnects, peakBacklog, elapsedNanos, 0L, latencies);
    }

    private void awaitAll(final Function<Loop, Future<?>> action) {
        final List<Future<?>> futures = new ArrayList<>(loops.length);
        for (final Loop loop : loops) {
            futures.add(action.apply(loop));
        }
        for (int i = 0; i < futures.size(); i++) {
            futures.get(i).awaitUninterruptibly();
        }
    }

    private static Histogram newHistogram() {
        return new Histogram(LOWEST_TRACKED_NANOS, HIGHEST_TRACKED_NANOS, SIGNIFICANT_DIGITS);
    }

    @Override
    public void close() {
        for (int i = 0; i < connections.size(); i++) {
            final Channel channel = connections.get(i).channel;
            if (channel != null) {
                channel.close();
            }
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    /**
     * Keeps the schedule in {@link Mode#LATENCY}: at every due instant it takes an idle connection and gives
     * it that instant to be timed from. It spins rather than sleeping, because the periods here are
     * microseconds and a parked thread wakes with far coarser granularity than that.
     * <p>
     * When nothing is idle the due instant does <i>not</i> move on. The requests which were owed pile up,
     * every one of them keeps the time it was owed at, and the tail of the distribution shows what the
     * server's slowness actually cost. That is the whole point of an open loop.
     */
    private final class Pacer extends Thread {
        private volatile boolean running = true;
        private volatile boolean resetRequested;
        private volatile long peakBacklog;

        private Pacer() {
            super("newa-perf-pacer");
            setDaemon(true);
        }

        @Override
        public void run() {
            long nextDueNanos = System.nanoTime();
            long peak = 0;
            while (running) {
                if (resetRequested) {
                    resetRequested = false;
                    nextDueNanos = System.nanoTime();
                    peak = 0;
                    peakBacklog = 0;
                }
                final long now = System.nanoTime();
                while (now - nextDueNanos >= 0) {
                    final Connection connection = idle.poll();
                    if (connection == null) {
                        final long overdue = (now - nextDueNanos) / periodNanos + 1;
                        if (overdue > peak) {
                            peak = overdue;
                            peakBacklog = peak;
                        }
                        break;
                    }
                    connection.dispatch(nextDueNanos);
                    nextDueNanos += periodNanos;
                }
                Thread.onSpinWait();
            }
        }

        private void reset() {
            resetRequested = true;
        }

        private long stopAndJoin() {
            running = false;
            try {
                join(TimeUnit.SECONDS.toMillis(5));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return peakBacklog;
        }
    }

    /**
     * One event loop's share of the run: its connections' counters and their latencies. Everything here is
     * touched by that one thread only, so none of it is synchronized.
     */
    private final class Loop {
        private final int index;
        private final EventLoop eventLoop;
        private final Histogram histogram = newHistogram();

        private long requests;
        private long bytes;
        private long badStatuses;
        private long ioErrors;
        private long reconnects;

        private volatile boolean running;

        private Loop(final int index,
                     final EventLoop eventLoop) {
            this.index = index;
            this.eventLoop = eventLoop;
        }

        private void reset() {
            histogram.reset();
            requests = 0;
            bytes = 0;
            badStatuses = 0;
            ioErrors = 0;
            reconnects = 0;
        }

        private Snapshot stopAndSnapshot() {
            running = false;
            return new Snapshot(this);
        }
    }

    /**
     * One loop's counters, read on that loop and safe to look at from anywhere afterwards.
     */
    private static final class Snapshot {
        private final long requests;
        private final long bytes;
        private final long badStatuses;
        private final long ioErrors;
        private final long reconnects;
        private final Histogram latencies;

        private Snapshot(final Loop loop) {
            requests = loop.requests;
            bytes = loop.bytes;
            badStatuses = loop.badStatuses;
            ioErrors = loop.ioErrors;
            reconnects = loop.reconnects;
            latencies = loop.histogram.copy();
        }
    }

    /**
     * One connection, and the single request travelling on it. The request object and the URI it points at
     * are both reused, so the send path allocates nothing.
     * <p>
     * Sharable because a connection outlives its channel: Tomcat closes a keep-alive connection after
     * {@code maxKeepAliveRequests} of them - a hundred, by default - and this handler is then added to the
     * pipeline of the replacement. It is never in two pipelines at once, which is what the annotation is
     * really promising.
     */
    @ChannelHandler.Sharable
    private final class Connection extends ChannelInboundHandlerAdapter implements Runnable {
        private final Loop loop;
        private final DefaultFullHttpRequest request;

        private Channel channel;
        private boolean busy;
        private long intendedNanos;
        private long dueNanos;
        private long pendingBytes;
        private boolean pendingOk;
        private boolean pendingClose;
        private int uriIndex;

        private Connection(final Loop loop) {
            this.loop = loop;
            request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    uris[0],
                    Unpooled.EMPTY_BUFFER
            );
            request.headers()
                    .set(HttpHeaderNames.HOST, host + ":" + port)
                    .set(HttpHeaderNames.ACCEPT, "application/json")
                    .set(HttpHeaderNames.CONTENT_LENGTH, 0);
        }

        private ChannelFuture connect() {
            final Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(loop.eventLoop)
                    .channel(Transport.socketChannel())
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) {
                            // no aggregator: the body is counted as it arrives and never assembled
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(Connection.this);
                        }
                    });
            final ChannelFuture future = bootstrap.connect(host, port);
            future.addListener(f -> {
                if (f.isSuccess()) {
                    channel = future.channel();
                    onConnected();
                    return;
                }
                loop.ioErrors++;
                reconnect();
            });
            return future;
        }

        /**
         * Re-establishes a connection the server closed. Without this a run against a server which caps
         * keep-alive requests reports whatever happened before the cap and then silence, which looks exactly
         * like a server that stopped answering.
         */
        private void reconnect() {
            if (!loop.running) {
                return;
            }
            try {
                loop.reconnects++;
                connect();
            } catch (final RejectedExecutionException e) {
                // the run is being wound up and the loop is no longer taking work
                loop.running = false;
            }
        }

        private void onConnected() {
            if (mode == Mode.THROUGHPUT) {
                if (loop.running) {
                    send(0L);
                }
                return;
            }
            idle.add(this);
        }

        /**
         * Called by the pacer, off the event loop: it hands over the instant this request was due and lets
         * the loop do the sending.
         *
         * @param due instant this request should have gone out at
         */
        private void dispatch(final long due) {
            dueNanos = due;
            try {
                loop.eventLoop.execute(this);
            } catch (final RejectedExecutionException ignored) {
                // the run is over
            }
        }

        @Override
        public void run() {
            if (loop.running && channel != null && channel.isActive()) {
                send(dueNanos);
                return;
            }
            idle.add(this);
        }

        private void send(final long intended) {
            if (busy) {
                throw new IllegalStateException("A second request on a connection which has one in flight");
            }
            busy = true;
            intendedNanos = intended;
            pendingBytes = 0;
            pendingOk = true;
            pendingClose = false;
            request.setUri(uris[uriIndex]);
            uriIndex = (uriIndex + 1) & (URI_RING - 1);
            channel.writeAndFlush(request);
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx,
                                final Object msg) {
            try {
                if (msg instanceof HttpResponse) {
                    final HttpResponse response = (HttpResponse) msg;
                    pendingOk = HttpResponseStatus.OK.equals(response.status());
                    // Tomcat closes a keep-alive connection after maxKeepAliveRequests - a hundred, by
                    // default - and says so on the last response. Sending into it anyway would lose that
                    // request and report a connection error the server never made
                    pendingClose = !HttpUtil.isKeepAlive(response);
                }
                if (msg instanceof HttpContent) {
                    pendingBytes += ((HttpContent) msg).content().readableBytes();
                    if (msg instanceof LastHttpContent) {
                        complete();
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void complete() {
            busy = false;
            if (pendingOk) {
                loop.requests++;
                loop.bytes += pendingBytes;
                if (mode == Mode.LATENCY) {
                    loop.histogram.recordValue(
                            Math.min(HIGHEST_TRACKED_NANOS, System.nanoTime() - intendedNanos));
                }
            } else {
                loop.badStatuses++;
            }
            if (pendingClose) {
                channel.close(); // channelInactive reconnects, and offers this connection again
                return;
            }
            if (mode == Mode.THROUGHPUT) {
                if (loop.running) {
                    send(0L);
                }
                return;
            }
            idle.add(this);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            if (busy) {
                busy = false;
                loop.ioErrors++;
            }
            ctx.pipeline().remove(this);
            reconnect();
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
            loop.ioErrors++;
            ctx.close();
        }
    }
}
