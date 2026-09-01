package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.Cores;
import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.HdrHistogram.Histogram;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The load client all three servers are measured with.
 * <p>
 * A <i>client</i> here is one WebSocket connection which subscribes to every channel of the run and then
 * only ever reads. It sends nothing after that: the load is the server's to generate, at the rate the run
 * offered it, and what this side measures is what arrived.
 * <p>
 * Three things are counted, and a row is only a result if all three agree:
 * <ul>
 * <li><b>How much arrived</b>, which divided by the window is the delivered rate. Against
 * {@code rate x channels x subscribers}, it is most of the answer.</li>
 * <li><b>How far the sequence moved</b> - the publication numbers of the first and the last message of the
 * window. A server whose publisher blocks on its slowest subscriber, and a server which queues what it
 * cannot deliver, both fall behind here while losing nothing: neither has any other way of admitting it.</li>
 * <li><b>Holes.</b> Every channel of every connection is a stream which promised to skip nothing, and this
 * is where that promise is checked rather than assumed.</li>
 * </ul>
 * Latency is one way. The instant of publication travels in the message, both processes are on one host and
 * therefore on one monotonic clock, and there is no round trip to inflate it - which is the only honest way
 * to time a fan-out, where the subscriber never answers.
 * <p>
 * The read path allocates nothing per message: the two fields it needs are found by name and accumulated
 * digit by digit where they lie, without the frame being decoded into anything. There is no frame
 * aggregator, because a tick is one frame and a composite
 * buffer per message would be the client measuring itself, and the UTF-8 validator is off for the same
 * reason - the messages are ASCII by construction.
 */
public final class WsLoadClient implements AutoCloseable {
    private static final long LOWEST_TRACKED_NANOS = 1000L;
    private static final long HIGHEST_TRACKED_NANOS = 60L * 1000 * 1000 * 1000;
    private static final int SIGNIFICANT_DIGITS = 3;

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_HTTP_BYTES = 8192;
    private static final int MAX_FRAME_BYTES = 65536;

    private static final String STOMP_SUBPROTOCOL = "v12.stomp";

    /**
     * What ends a STOMP frame. It is a NUL byte, not whitespace, and a frame without it is never answered.
     */
    private static final char STOMP_END = '\0';

    private final String host;
    private final int port;
    private final int clients;
    private final int channels;
    private final boolean stomp;
    private final URI uri;

    private final MultiThreadIoEventLoopGroup group;
    private final Loop[] loops;
    private final List<Connection> connections = new ArrayList<>();

    /**
     * The first thing which went wrong on the client's own side of the run. A frame it could not read is a
     * fault of this instrument, not a subscriber the server disconnected, and counting the one as the other
     * is how a broken client comes to look like a slow server. So it is kept, and the run refuses to
     * produce a result at all.
     */
    private final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * @param host     to subscribe to
     * @param port     to subscribe to
     * @param clients  connections to open, each subscribing to every channel
     * @param channels the server is publishing into
     * @param stomp    whether the server speaks STOMP rather than taking a subscription as plain text
     */
    public WsLoadClient(final String host,
                        final int port,
                        final int clients,
                        final int channels,
                        final boolean stomp) {
        this.host = host;
        this.port = port;
        this.clients = clients;
        this.channels = channels;
        this.stomp = stomp;
        this.uri = URI.create("ws://" + host + ":" + port + WsPayload.PATH);

        final int threads = Math.min(Cores.clientThreads(), clients);
        group = new MultiThreadIoEventLoopGroup(threads, Transport.ioHandlerFactory());

        loops = new Loop[threads];
        int index = 0;
        for (final EventExecutor executor : group) {
            loops[index] = new Loop(index, (EventLoop) executor);
            index++;
        }
    }

    /**
     * Connects and subscribes every client, lets the stream run for the warmup, and resets the counters.
     * Split from {@link #measure(int)} so that a caller can sample the server's own statistics either side
     * of the measured window and not of the warmup.
     *
     * @param warmupSeconds to run before the counters are reset
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void startAndWarmUp(final int warmupSeconds) throws InterruptedException {
        connect();
        if (warmupSeconds > 0) {
            TimeUnit.SECONDS.sleep(warmupSeconds);
        }
        reset();
    }

    /**
     * Measures. {@link #close()} gives the threads and sockets back.
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
        final List<Promise<Void>> ready = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            final Connection connection = new Connection(loops[i % loops.length]);
            connections.add(connection);
            ready.add(connection.connect());
        }
        for (int i = 0; i < ready.size(); i++) {
            final Promise<Void> promise = ready.get(i).await();
            if (!promise.isSuccess()) {
                throw new IllegalStateException("Client " + i + " did not subscribe to "
                        + host + ":" + port, promise.cause());
            }
        }
    }

    private void reset() {
        awaitAll(loop -> loop.eventLoop.submit(loop::reset));
    }

    /**
     * Collects what the loops counted. The counting is done <i>on</i> each loop, in the same task which
     * stops it: messages still arriving while the run is being wound up would otherwise be seen
     * half-counted from here, with a frame added to one total and its sequence missing from another.
     *
     * @param elapsedNanos the measured window lasted
     * @return what every loop counted, added up
     */
    private LoadResult stop(final long elapsedNanos) {
        final Throwable failed = failure.get();
        if (failed != null) {
            throw new IllegalStateException("The load client could not read what the server sent, so "
                    + "nothing it counted means anything", failed);
        }

        final Snapshot[] snapshots = new Snapshot[loops.length];
        awaitAll(loop -> loop.eventLoop.submit(() -> snapshots[loop.index] = loop.stopAndSnapshot()));

        long frames = 0;
        long bytes = 0;
        long published = 0;
        long gaps = 0;
        long reordered = 0;
        long dropped = 0;
        final Histogram latencies = newHistogram();

        for (int i = 0; i < snapshots.length; i++) {
            final Snapshot snapshot = snapshots[i];
            frames += snapshot.frames;
            bytes += snapshot.bytes;
            published += snapshot.published;
            gaps += snapshot.gaps;
            reordered += snapshot.reordered;
            dropped += snapshot.dropped;
            latencies.add(snapshot.latencies);
        }
        return LoadResult.fanout(
                clients, frames, bytes, published, gaps, reordered, dropped, elapsedNanos, latencies);
    }

    private void awaitAll(final Function<Loop, Future<?>> action) {
        final List<Future<?>> futures = new ArrayList<>(loops.length);
        for (int i = 0; i < loops.length; i++) {
            futures.add(action.apply(loops[i]));
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
     * One event loop's share of the run. Everything here is touched by that one thread only, so none of it
     * is synchronized.
     */
    private final class Loop {
        private final int index;
        private final EventLoop eventLoop;
        private final Histogram histogram = newHistogram();
        private final List<Connection> subscribers = new ArrayList<>();

        private long frames;
        private long bytes;
        private long gaps;
        private long reordered;
        private long dropped;

        private volatile boolean running = true;

        private Loop(final int index,
                     final EventLoop eventLoop) {
            this.index = index;
            this.eventLoop = eventLoop;
        }

        private void reset() {
            histogram.reset();
            frames = 0;
            bytes = 0;
            gaps = 0;
            reordered = 0;
            // dropped is deliberately not reset. A subscriber the server let go never comes back, so one
            // lost during the warmup is missing from the window too - and at a rate past what a server can
            // take that is exactly when it goes. Zeroing it here would turn the most interesting row of a
            // sweep into an empty one: nobody left to deliver to, and nothing to say why
            for (int i = 0; i < subscribers.size(); i++) {
                subscribers.get(i).rebase();
            }
        }

        private Snapshot stopAndSnapshot() {
            running = false;
            long published = 0;
            for (int i = 0; i < subscribers.size(); i++) {
                published += subscribers.get(i).span();
            }
            return new Snapshot(this, published);
        }
    }

    /**
     * One loop's counters, read on that loop and safe to look at from anywhere afterwards.
     */
    private static final class Snapshot {
        private final long frames;
        private final long bytes;
        private final long published;
        private final long gaps;
        private final long reordered;
        private final long dropped;
        private final Histogram latencies;

        private Snapshot(final Loop loop,
                         final long published) {
            frames = loop.frames;
            bytes = loop.bytes;
            this.published = published;
            gaps = loop.gaps;
            reordered = loop.reordered;
            dropped = loop.dropped;
            latencies = loop.histogram.copy();
        }
    }

    /**
     * One subscriber: a connection, and the state of the one stream it reads from each channel.
     * <p>
     * That state is the whole check. A subscriber takes the first message it sees as its baseline and then
     * requires every next one to carry the sequence after it. A repeat of the baseline is allowed once and
     * once only: newa's contract says a publication may be seen twice, in the snapshot which is sent on
     * subscription and then again as the update, and that is the single place it can happen. Anything else -
     * a number skipped, a number gone backwards, a second repeat - is a hole, and a run with holes is not a
     * result.
     */
    private final class Connection extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final Loop loop;

        private final long[] baseSequence = new long[channels];
        private final long[] lastSequence = new long[channels];
        private final boolean[] started = new boolean[channels];
        private final boolean[] repeatAllowed = new boolean[channels];

        private Channel channel;
        private Promise<Void> ready;

        private Connection(final Loop loop) {
            this.loop = loop;
        }

        private Promise<Void> connect() {
            ready = loop.eventLoop.newPromise();
            loop.eventLoop.execute(() -> loop.subscribers.add(this));

            final WebSocketClientProtocolConfig config = WebSocketClientProtocolConfig.newBuilder()
                    .webSocketUri(uri)
                    .version(WebSocketVersion.V13)
                    .subprotocol(stomp ? STOMP_SUBPROTOCOL : null)
                    .maxFramePayloadLength(MAX_FRAME_BYTES)
                    .handshakeTimeoutMillis(HANDSHAKE_TIMEOUT_MILLIS)
                    .withUTF8Validator(false) // the messages are ASCII by construction, and validating
                    // every one of them again would be the client spending its half of the machine on
                    // itself
                    .build();

            final Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(loop.eventLoop)
                    .channel(Transport.socketChannel())
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) {
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(MAX_HTTP_BYTES));
                            ch.pipeline().addLast(new WebSocketClientProtocolHandler(config));
                            ch.pipeline().addLast(Connection.this);
                        }
                    });

            final ChannelFuture future = bootstrap.connect(host, port);
            future.addListener(f -> {
                if (f.isSuccess()) {
                    channel = future.channel();
                    return;
                }
                ready.tryFailure(f.cause());
            });
            return ready;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx,
                                       final Object event) {
            if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                if (stomp) {
                    // the broker answers CONNECTED, and only then is there a session to subscribe in
                    send(ctx, "CONNECT\naccept-version:1.2\nheart-beat:0,0\nhost:" + host + "\n\n"
                            + STOMP_END);
                    return;
                }
                subscribe(ctx);
            }
        }

        private void subscribe(final ChannelHandlerContext ctx) {
            for (int i = 0; i < channels; i++) {
                if (stomp) {
                    send(ctx, "SUBSCRIBE\nid:" + i + "\ndestination:" + WsPayload.TOPIC
                            + WsPayload.channelId(i) + "\n\n" + STOMP_END);
                } else {
                    send(ctx, WsPayload.SUBSCRIBE + WsPayload.channelId(i));
                }
            }
            ready.trySuccess(null);
        }

        private void send(final ChannelHandlerContext ctx,
                          final String text) {
            ctx.writeAndFlush(new TextWebSocketFrame(text));
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx,
                                    final WebSocketFrame frame) {
            if (frame instanceof ContinuationWebSocketFrame) {
                throw new IllegalStateException("A message arrived fragmented. The offsets this client "
                        + "reads by assume one frame per message");
            }
            if (!(frame instanceof TextWebSocketFrame)) {
                return;
            }

            final ByteBuf content = frame.content();
            final int body = WsPayload.bodyStart(content);
            if (body < 0) {
                // a STOMP CONNECTED, or a refusal in words from a server which did not like the command
                if (stomp && !ready.isDone() && isConnected(content)) {
                    subscribe(ctx);
                }
                return;
            }
            if (!loop.running) {
                return;
            }

            final long sequence = WsPayload.readSequence(content, body);
            final long published = WsPayload.readPublishedNanos(content, body);
            final int channelIndex = channelOf(content, body);

            if (!started[channelIndex]) {
                started[channelIndex] = true;
                repeatAllowed[channelIndex] = true;
                baseSequence[channelIndex] = sequence;
                lastSequence[channelIndex] = sequence;
                return; // the baseline, which belongs to no window
            }

            final long expected = lastSequence[channelIndex] + 1;
            if (sequence == expected) {
                lastSequence[channelIndex] = sequence;
            } else if (sequence > expected) {
                loop.gaps += sequence - expected; // messages the one which just arrived went ahead of
                lastSequence[channelIndex] = sequence;
            } else if (sequence == lastSequence[channelIndex] && repeatAllowed[channelIndex]) {
                repeatAllowed[channelIndex] = false;
                return; // the one repeat the subscription contract allows: a publication seen in the
                // snapshot and then again as an update
            } else {
                loop.reordered++; // it arrived, only late. The high water mark stays where it is, so the
                // window still spans what was published rather than shrinking back
            }
            repeatAllowed[channelIndex] = false;

            loop.frames++;
            loop.bytes += content.readableBytes();
            loop.histogram.recordValue(
                    Math.min(HIGHEST_TRACKED_NANOS, Math.max(0L, System.nanoTime() - published)));
        }

        private int channelOf(final ByteBuf content,
                              final int body) {
            final int index = WsPayload.readChannel(content, body);
            if (index < 0 || index >= channels) {
                throw new IllegalStateException("A message arrived for channel " + index
                        + ", which this run does not have");
            }
            return index;
        }

        private boolean isConnected(final ByteBuf content) {
            return content.readableBytes() > 0 && content.getByte(content.readerIndex()) == 'C';
        }

        /**
         * Takes the stream as it stands now as the beginning of the window, so that what the warmup
         * delivered is not counted and the sequence numbers still line up across the boundary.
         */
        private void rebase() {
            for (int i = 0; i < channels; i++) {
                baseSequence[i] = lastSequence[i];
            }
        }

        /**
         * @return how many publications this subscriber should have been given over the window, taken from
         *         how far the sequence moved rather than from what arrived
         */
        private long span() {
            long total = 0;
            for (int i = 0; i < channels; i++) {
                total += lastSequence[i] - baseSequence[i];
            }
            return total;
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            if (loop.running) {
                loop.dropped++; // the server let this subscriber go, which is what the non-skipping
                // policy does instead of skipping. The client does not reconnect: a subscriber which was
                // dropped is the result, not noise to be recovered from
            }
            ready.tryFailure(new IllegalStateException(
                    "The server closed the connection before the subscription was made"));
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
            if (cause instanceof java.io.IOException) {
                if (loop.running) {
                    loop.dropped++; // the peer went away, which is a result
                }
            } else {
                failure.compareAndSet(null, cause); // anything else is this client failing to read, and
                // the run has to say so rather than report it as a server which disconnected somebody
            }
            ready.tryFailure(cause);
            ctx.close();
        }
    }
}
