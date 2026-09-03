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

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A WebSocket server in one line:
 * <pre>{@code
 * WsServer.start(9010, new SimpleWsApiBuilder(1).withReceiver((s, msg) -> s.send(msg)).build())
 *         .awaitClose();
 * }</pre>
 * <p>
 * It assembles exactly the pipeline this module documents, out of the same public handlers a pipeline
 * written by hand is made of:
 * <pre>
 * Client --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt; [WebSocketServerCompressionHandler] --&gt;
 *            WsApiHandler --&gt; [your handlers] --&gt; HandshakeOnlyHandler
 * </pre>
 * Nothing is hidden and nothing is one-way: {@link #pipeline()} hands the same initializer to a
 * {@link io.netty.bootstrap.ServerBootstrap} of your own, and everything below the pipeline - the transport,
 * the threads, the channel options - stays on {@link NettyServerBuilder}, which
 * {@link #start(NettyServerBuilder)} takes.
 * <p>
 * What belongs to the api - the path, the ping interval, the back pressure policy, the observers and what
 * receives the frames - is the {@link WsApiBuilder}'s, and is not repeated here.
 */
public final class WsServer {
    /**
     * How large the handshake request may be. Nothing after the handshake goes through the aggregator, so
     * this bounds one request per connection rather than anything a session sends.
     */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;

    /**
     * The whole server in one call, with everything at its default.
     *
     * @param port to listen on, or 0 to let the OS pick one.
     * @param api to serve, from a {@link WsApiBuilder}.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final int port,
                                    final WsApi api) throws InterruptedException {
        return of(api).start(port);
    }

    /**
     * @param api to serve, from {@link SimpleWsApiBuilder} or
     *            {@link io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder}.
     * @return a server to configure and then start.
     */
    public static WsServer of(final WsApi api) {
        return new WsServer(api);
    }

    private final WsApi api;
    private final List<Supplier<ChannelHandler>> handlers = new ArrayList<>();

    private ChannelErrorHandler channelErrorHandler = new StdErrChannelErrorHandler();
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    private boolean compression;

    private WsServer(final WsApi api) {
        this.api = api;
    }

    /**
     * Negotiates permessage-deflate with a client which asks for it. Off by default: it costs CPU per
     * frame, and a fan-out which already sends small frames gains little.
     *
     * @return this builder.
     */
    public WsServer withCompression() {
        this.compression = true;
        return this;
    }

    /**
     * @param channelErrorHandler told about channel failures, null to say nothing.
     * @return this builder.
     */
    public WsServer withChannelErrorHandler(final ChannelErrorHandler channelErrorHandler) {
        this.channelErrorHandler = channelErrorHandler;
        return this;
    }

    /**
     * @param bytes the handshake request may be, {@link #DEFAULT_MAX_CONTENT_LENGTH} by default.
     * @return this builder.
     */
    public WsServer withMaxContentLength(final int bytes) {
        this.maxContentLength = bytes;
        return this;
    }

    /**
     * Adds a handler of your own, in the order added.
     *
     * <p>It goes <i>behind</i> the api handler, which is where a request that is not the handshake ends up:
     * the handshake handler passes on a uri it does not recognise. That is how one port serves both the
     * websocket and an HTTP api - put a {@code RestApiHandler} here. Note that it is the handler which goes
     * here, not {@code RestServer.pipeline()}: the codec and the aggregator are already in front of it, and
     * a second pair of them would decode everything twice.
     *
     * <p>A {@link HandshakeOnlyHandler} stands behind whatever is added here, so a request neither the
     * handshake nor any of these took closes the connection rather than holding it open unanswered.
     *
     * @param handler asked for one handler per channel, because a handler is rarely {@code @Sharable}.
     * @return this builder.
     */
    public WsServer withHandler(final Supplier<ChannelHandler> handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * The pipeline of one channel, to hand to a {@link io.netty.bootstrap.ServerBootstrap} assembled by
     * hand. A fresh set of handlers is built per channel - none of them is {@code @Sharable}.
     *
     * @return the initializer of every accepted channel.
     */
    public ChannelInitializer<Channel> pipeline() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                initPipeline(ch.pipeline());
            }
        };
    }

    /**
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final int port) throws InterruptedException {
        return start(new NettyServerBuilder().port(port));
    }

    /**
     * Starts on a bootstrap of your own - the way to reach the transport, the thread counts, the water
     * marks a fan-out is paced by, or any channel option, without giving up the pipeline this class
     * assembles.
     *
     * @param bootstrap to start on; its child handler is set here.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final NettyServerBuilder bootstrap) throws InterruptedException {
        return bootstrap.childHandler(pipeline()).start();
    }

    private void initPipeline(final ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxContentLength, true));

        if (compression) {
            pipeline.addLast(new WebSocketServerCompressionHandler(0));
        }

        pipeline.addLast(new WsApiHandler(api, channelErrorHandler));

        for (int i = 0; i < handlers.size(); i++) {
            pipeline.addLast(handlers.get(i).get());
        }

        // last, so everything added above answers first. Without it a request which is not the handshake
        // and which nobody took would sit at the end of the pipeline, discarded in silence, holding a
        // connection which nothing is on a timer to close
        pipeline.addLast(new HandshakeOnlyHandler(channelErrorHandler));
    }
}
