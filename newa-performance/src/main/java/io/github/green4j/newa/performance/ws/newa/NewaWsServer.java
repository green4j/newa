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

package io.github.green4j.newa.performance.ws.newa;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.Transport;
import io.github.green4j.newa.performance.ws.WsPayload;
import io.github.green4j.newa.performance.ws.WsServer;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiHandler;
import io.github.green4j.newa.websocket.subscriptions.Channel;
import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;
import io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * newa's side of the fan-out benchmark: a subscription channel per publisher, written the way the
 * framework's own example writes one, with nothing added for the occasion.
 * <p>
 * <b>It is built without {@code withSkipOnBackPressure()}</b>, which is the whole point of the run: a
 * subscriber which cannot keep up is disconnected rather than having frames skipped, so what the rows report
 * is the rate at which every subscriber still got everything, in order.
 * <p>
 * A publication is rendered once, with green-jelly, into a buffer the channel reuses - no event object ever
 * exists - and every subscriber is handed a retained duplicate of the frame copied out of it. The frame has
 * to be that copy: the duplicates are read later, on the subscribers' loops, so the next publication would
 * corrupt frames still on their way out.
 * <p>
 * Nothing is added to the pipeline for the occasion: no compression, because neither Spring server has any,
 * and no observer, because a benchmark should not measure its own instrumentation.
 */
public final class NewaWsServer implements WsServer {
    private static final String LOCAL_IFC = "127.0.0.1";

    private static final int MAX_REQUEST_BYTES = 8192;

    /**
     * Past the high mark the channel stops being writable, which without skipping closes the session. It is
     * {@link WsServer#outboundBudgetBytes(int, long, int)}, the allowance every server here is given, with
     * the low mark at half so a session which caught up is not immediately at the line again.
     *
     * @param channels    a subscriber takes
     * @param rate        each of them publishes at
     * @param messageSize bytes a published message is
     * @return the marks this run holds a subscriber to
     */
    private static WriteBufferWaterMark waterMarks(final int channels,
                                                   final long rate,
                                                   final int messageSize) {
        final int high = WsServer.outboundBudgetBytes(channels, rate, messageSize);
        return new WriteBufferWaterMark(high / 2, high);
    }

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final io.netty.channel.Channel channel;
    private final QuoteChannel quotes;
    private final Quotes[] channels;
    private final int port;

    private NewaWsServer(final EventLoopGroup bossGroup,
                         final EventLoopGroup workerGroup,
                         final io.netty.channel.Channel channel,
                         final QuoteChannel quotes,
                         final Quotes[] channels) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.channel = channel;
        this.quotes = quotes;
        this.channels = channels;
        this.port = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /**
     * @param port     to listen on, or 0 for an ephemeral one
     * @param workers  event loops to deliver from - the half of the machine the load client left
     * @param channels to publish into
     * @param messageSize bytes a published message is
     * @param rate     each channel publishes at, which the allowance follows from
     * @return the running server
     * @throws InterruptedException if the calling thread is interrupted while binding
     */
    public static NewaWsServer start(final int port,
                                     final int workers,
                                     final int channels,
                                     final int messageSize,
                                     final long rate) throws InterruptedException {
        final QuoteChannel quotes = new QuoteChannel(messageSize);
        final Quotes[] entities = new Quotes[channels];
        for (int i = 0; i < channels; i++) {
            entities[i] = quotes.getOrCreateEntitySubscriptions(WsPayload.channelId(i));
        }
        final Subscriptions subscriptions = new Subscriptions(quotes);

        // the api owns what receives, so it is built once the receiver it hands frames to exists
        final WsApi api = new SubscriptionWsApiBuilder(1)
                .withPathPrefix("ws")
                .withReceiver(subscriptions)
                .withPingIntervalMs(0) // a ping frame would show up in the subscriber's counts as a
                .withReadTimeoutMs(0)  // frame nobody published, and a timer per session is a cost the
                // benchmark did not have when its numbers were recorded. Both are on by default, and a
                // measuring instrument has to keep measuring the same thing
                .build(); // no skipping and no observer either

        final EventLoopGroup bossGroup =
                new MultiThreadIoEventLoopGroup(1, Transport.ioHandlerFactory());
        final EventLoopGroup workerGroup =
                new MultiThreadIoEventLoopGroup(workers, Transport.ioHandlerFactory());

        final RestApi stats = buildStatsApi();

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(Transport.serverSocketChannel())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        waterMarks(channels, rate, messageSize))
                .childHandler(new ChannelInitializer<io.netty.channel.Channel>() {
                    @Override
                    protected void initChannel(final io.netty.channel.Channel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));
                        ch.pipeline().addLast(new WsApiHandler(api, NewaWsServer::onError));
                        // whatever was not the websocket path carries on to here: the handshake handler
                        // forwards a request whose uri it does not recognise, and the api handler only
                        // ever consumes websocket frames. That is how one port serves both the
                        // subscriptions and the statistics the benchmark reads the server's cost from
                        ch.pipeline().addLast(
                                new RestApiHandler(stats, new JsonErrorHandler(), NewaWsServer::onError));
                    }
                });

        try {
            final io.netty.channel.Channel channel = bootstrap
                    .bind(InetAddress.getByName(LOCAL_IFC), port)
                    .sync()
                    .channel();
            return new NewaWsServer(bossGroup, workerGroup, channel, quotes, entities);
        } catch (final Exception e) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            throw new IllegalStateException("Could not bind the newa server to port " + port, e);
        }
    }

    private static void onError(final io.netty.channel.Channel ch,
                                final Throwable cause) {
        if (cause instanceof IOException) {
            // every run ends with the client dropping its connections, and a peer going away is not
            // the server's fault
            return;
        }
        System.err.println("Channel error: " + cause);
        cause.printStackTrace(System.err);
    }

    private static RestApi buildStatsApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "newa Performance API",
                "What the fan-out benchmark reads the server's own cost from",
                1,
                "0.0.1"
        );
        builder.getTxt("/perf/stats", (context, output) -> output.append(JvmStats.current().render()));
        return builder.build();
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public void publish(final int channel) {
        channels[channel].publish();
    }

    @Override
    public void close() {
        quotes.close();
        channel.close().awaitUninterruptibly();
        bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    /**
     * Turns a subscriber's {@code SUB:cNN} into a subscription. It runs on the session's own event loop,
     * which is where {@code Channel} needs the call to come from, so the entity is joined and its snapshot
     * written before this returns.
     */
    private static final class Subscriptions implements Receiver {
        private final QuoteChannel quotes;

        private Subscriptions(final QuoteChannel quotes) {
            this.quotes = quotes;
        }

        @Override
        public void receive(final ClientSession session,
                            final CharSequence message) {
            final String command = message.toString();
            if (!command.startsWith(WsPayload.SUBSCRIBE)) {
                session.send("Error: expected " + WsPayload.SUBSCRIBE + "<channel>, got " + command);
                return;
            }
            final String entityId = command.substring(WsPayload.SUBSCRIBE.length());
            if (quotes.subscribeForKnownOnly(session, entityId) == 0) {
                session.send("Error: unknown channel " + entityId);
            }
        }
    }

    /**
     * The channel every subscription is made in. One entity per benchmark channel, created up front, so a
     * subscriber can only ever join one which a publisher is already publishing into.
     */
    private static final class QuoteChannel extends Channel<Quotes> {
        private final int messageSize;

        private QuoteChannel(final int messageSize) {
            this.messageSize = messageSize;
        }

        @Override
        protected Quotes newEntitySubscriptions(final String entityId) {
            return new Quotes(entityId, messageSize);
        }
    }

    /**
     * The subscribers of one channel, and the publication into them.
     */
    private static final class Quotes extends EntitySubscriptions {
        private final int channel;
        private final String pad;

        /**
         * Where a publication is written: one per channel, touched only by that channel's publisher thread
         * and reused. After the first publication nothing is allocated but the frame itself.
         */
        private final AsciiByteArrayWriter buffer;
        private final JsonGenerator json = new JsonGenerator(false);

        /**
         * The state of the entity, written before every fan-out. Every field of a message is derived from
         * it, so this one number is the whole state and a snapshot is rendered from it rather than kept.
         */
        private volatile long lastSequence;

        private Quotes(final String entityId,
                       final int messageSize) {
            super(entityId);
            this.channel = channelOf(entityId);
            this.pad = WsPayload.padding(messageSize);
            this.buffer = new AsciiByteArrayWriter(messageSize);
            json.setOutput(buffer);
        }

        private static int channelOf(final String entityId) {
            return Integer.parseInt(entityId.substring(1));
        }

        /**
         * Publishes one tick. Called from this channel's publisher thread and no other - two publishers of
         * one entity would have no defined order between them, and nothing here could invent one.
         */
        void publish() {
            final long sequence = publicationSequence() + 1; // what publishAndRelease is about to assign,
            // because this thread is the only one which ever publishes here

            lastSequence = sequence; // the state, before the fan-out: that ordering is what makes it
            // visible to a session subscribing at this very moment

            buffer.clear();
            json.reset();
            WsPayload.render(json, channel, sequence, System.nanoTime(), pad);
            json.eoj();

            final ByteBuf frame = PooledByteBufAllocator.DEFAULT.directBuffer(buffer.length());
            frame.writeBytes(buffer.array(), buffer.start(), buffer.length());
            publishAndRelease(frame); // published unconditionally, even into an empty channel: skipping
            // the call would skip the bump of the sequence, and the numbers a subscriber counts holes by
            // would then stop meaning what they say
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            final long sequence = lastSequence;
            if (sequence == 0) {
                return; // nothing published yet, so the first update is the beginning of the stream
            }
            // a buffer of its own, because this thread may not touch the publisher's. Once per
            // subscription, off the measured window, so what it allocates is nobody's cost
            session.send(Unpooled.wrappedBuffer(
                    WsPayload.render(channel, sequence, System.nanoTime(), pad)));
        }
    }
}
