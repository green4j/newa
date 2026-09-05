/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.server.DecoderFailureHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.RequestDeadlineHandler;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A WebSocket server in one line:
 * <pre>{@code
 * WsApi api = new WsApiBuilder(1)
 *         .withTextReceiver((session, message, last) -> session.sendText(message))
 *         .build();
 *
 * new Life().run(() -> WsServer.start(api, 9010));
 * }</pre>
 * <p>
 * It assembles this pipeline, out of the same public handlers a pipeline written by hand is made of:
 * <pre>
 * Client --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt; [RequestDeadlineHandler] --&gt;
 *            [ResponseDeadlineHandler] --&gt; DecoderFailureHandler --&gt; OriginCheckHandler --&gt;
 *            [WebSocketServerCompressionHandler] --&gt; WsApiHandler --&gt; [your handlers] --&gt;
 *            HandshakeOnlyHandler
 * </pre>
 * Nothing is hidden and nothing is one-way: {@link #pipeline()} hands the same initializer to a
 * {@link io.netty.bootstrap.ServerBootstrap} of your own, and everything below the pipeline - the transport,
 * the threads, the channel options - stays on {@link NettyServerBuilder}, which
 * {@link #start(NettyServerBuilder)} takes.
 * <p>
 * What belongs to the api - the path, the ping interval, the back pressure policy, the observers and what
 * receives the frames - is the {@link AbstractWsApiBuilder}'s, and is not repeated here.
 */
public final class WsServer {
    /**
     * How large the <b>body</b> of the handshake request may be - not its headers, which the codec bounds
     * instead ({@link #withMaxInitialLineLength(int)} and {@link #withMaxHeaderSize(int)}), and not anything
     * written back. Nothing after the handshake goes through the aggregator either, so this bounds one
     * request per connection rather than anything a session sends - that is
     * {@link #DEFAULT_MAX_FRAME_PAYLOAD_LENGTH}. A body past it is answered {@code 413} and the connection
     * closed.
     */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;

    /**
     * How long the request line of the handshake - the method, the whole uri and the version - may be, unless
     * {@link #withMaxInitialLineLength(int)} says otherwise. Netty's own default.
     */
    public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH;

    /**
     * How large the header block of the handshake may be, all of it together, unless
     * {@link #withMaxHeaderSize(int)} says otherwise. Netty's own default. This is the one a handshake meets:
     * the browser's cookies for this origin, a bearer token and a long
     * {@code Sec-WebSocket-Protocol} all travel in it, and it is the only HTTP request a session ever makes.
     */
    public static final int DEFAULT_MAX_HEADER_SIZE = HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE;

    /**
     * How large a single frame may be once the handshake is over, unless
     * {@link #withMaxFramePayloadLength(int)} says otherwise. Netty's own default, and the size the rest of
     * this module is written around.
     */
    public static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65536;

    /**
     * How long a handshake or a frame has to arrive, and how long a unit of what is written has to reach the
     * peer, unless {@link #withRequestDeadlineMs(int)} or {@link #withResponseDeadlineMs(int)} says
     * otherwise. The same number the HTTP servers of this framework use, and it is what guards the window
     * before a session exists: until the handshake there is no ping and no read timeout, and nothing else
     * would ever close a connection which opened and said nothing.
     */
    public static final int DEFAULT_DEADLINE_MS = 30_000;

    /**
     * The whole server in one call, with everything at its default - which includes the <b>loopback</b>, so
     * nothing outside this machine can reach it. {@link #start(WsApi, String, int)} opens it up.
     *
     * @param api to serve, from an {@link AbstractWsApiBuilder}.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final WsApi api,
                                    final int port) throws InterruptedException {
        return of(api).start(port);
    }

    /**
     * The whole server in one call, on an interface of your own: the address of the network it belongs on,
     * or {@link NettyServerBuilder#ANY_HOST} for every interface. The two-argument form leaves it on the
     * loopback.
     *
     * @param api to serve, from an {@link AbstractWsApiBuilder}.
     * @param host to bind, or {@link NettyServerBuilder#ANY_HOST} for every interface.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final WsApi api,
                                    final String host,
                                    final int port) throws InterruptedException {
        return of(api).start(host, port);
    }

    /**
     * @param api to serve, from {@link WsApiBuilder} or
     *            {@link io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder}.
     * @return a server to configure and then start.
     */
    public static WsServer of(final WsApi api) {
        return new WsServer(api);
    }

    private final WsApi api;
    private final List<Supplier<ChannelHandler>> handlers = new ArrayList<>();

    private ChannelErrorHandler channelErrorHandler = new StdErrChannelErrorHandler();
    private OriginPolicy originPolicy = OriginPolicy.sameOrigin();
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    private int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
    private int maxHeaderSize = DEFAULT_MAX_HEADER_SIZE;
    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private int requestDeadlineMs = DEFAULT_DEADLINE_MS;
    private int responseDeadlineMs = DEFAULT_DEADLINE_MS;
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
     * @param bytes the body of the handshake request may be, {@link #DEFAULT_MAX_CONTENT_LENGTH} by default.
     *              Headers are not counted in it and are bounded by the codec instead.
     * @return this builder.
     */
    public WsServer withMaxContentLength(final int bytes) {
        this.maxContentLength = bytes;
        return this;
    }

    /**
     * Sets how long the request line of the handshake may be - the method, the whole uri, the version. A uri
     * past it is answered {@code 414} and the connection closed, because a decoder which has refused a
     * request line never reads that connection again.
     *
     * @param bytes the request line may be, {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH} by default.
     * @return this builder.
     */
    public WsServer withMaxInitialLineLength(final int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException(
                    "A request line no byte fits in would refuse every request: " + bytes);
        }
        this.maxInitialLineLength = bytes;
        return this;
    }

    /**
     * Sets how large the header block of the handshake may be, all of its headers together. A block past it
     * is answered {@code 431} and the connection closed, for the same reason. This is the limit a browser
     * reaches first: the cookies of this origin travel in the handshake.
     *
     * @param bytes the headers of the handshake may be, {@link #DEFAULT_MAX_HEADER_SIZE} by default.
     * @return this builder.
     */
    public WsServer withMaxHeaderSize(final int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException(
                    "A header block no byte fits in would refuse every request: " + bytes);
        }
        this.maxHeaderSize = bytes;
        return this;
    }

    /**
     * @param bytes a single frame may carry, {@link #DEFAULT_MAX_FRAME_PAYLOAD_LENGTH} by default. This is
     *              the one which bounds a session: {@link #withMaxContentLength(int)} bounds the handshake
     *              request and nothing after it. A frame past this is answered with close status 1009 and
     *              the connection goes.
     * @return this builder.
     */
    public WsServer withMaxFramePayloadLength(final int bytes) {
        this.maxFramePayloadLength = bytes;
        return this;
    }

    /**
     * Sets how long a request has to arrive once it has begun arriving - the handshake first of all, and
     * every frame after it.
     *
     * <p>It guards the window nothing else does. A session pings what has gone quiet and closes what has
     * stopped answering, but a session begins at the handshake: before it there is no ping interval, no read
     * timeout and nothing at all on a timer, and Netty's own handshake timeout does not cover it either -
     * that clock starts when the handshake <i>request</i> arrives, so a connection which opens and sends
     * nothing is not covered by it.
     *
     * <p>Nothing has to be taken out of the pipeline once a session begins, and nothing has to be sized
     * around the ping interval: a session which is merely quiet is not being read from, so nothing is
     * running. What the handler goes on judging after the handshake is a frame which has begun arriving and
     * has not finished - which is the same rule, applied to what a session sends.
     *
     * @param requestDeadlineMs a handshake or a frame has to arrive within, {@link #DEFAULT_DEADLINE_MS} by
     *                          default, 0 to let one arrive as slowly as the peer likes.
     * @return this builder.
     */
    public WsServer withRequestDeadlineMs(final int requestDeadlineMs) {
        this.requestDeadlineMs = requestDeadlineMs;
        return this;
    }

    /**
     * Sets how long one unit of what is written - 64K - has to reach the peer, which is what judges a session
     * whose peer has stopped taking its frames.
     *
     * <p>Nothing is timed while nothing is owed: a session with nothing to broadcast is on no clock. Nothing
     * is queued for a peer which is behind either - {@code ClientSession} skips or fails a frame the channel
     * cannot take rather than holding it - so what this bounds is the connection itself, which the session's
     * own {@code readTimeoutMs} would otherwise be the only thing to reach.
     *
     * @param responseDeadlineMs a unit of what is written has to reach the peer within,
     *                           {@link #DEFAULT_DEADLINE_MS} by default, 0 to wait for a peer as long as it
     *                           likes.
     * @return this builder.
     */
    public WsServer withResponseDeadlineMs(final int responseDeadlineMs) {
        this.responseDeadlineMs = responseDeadlineMs;
        return this;
    }

    /**
     * Reads the {@code Origin} of every handshake and refuses the ones the policy does not allow.
     * {@link OriginPolicy#sameOrigin()} by default, which is what a server a browser reaches directly needs
     * and what a server reached by nothing else pays nothing for: a caller which sends no {@code Origin} is
     * not a browser and is let through.
     *
     * <p>The default is a decision, not an omission: the same-origin policy does not cover a handshake, so a
     * page on any site can open a session here and the browser will attach the cookies of <b>this</b> origin
     * to it. {@link OriginPolicy#allowing(String...)} names the origins of a page served from somewhere
     * else, and {@link OriginPolicy#any()} says out loud that something in front of this server decides it.
     *
     * @param originPolicy which origins may complete the handshake, never null - {@code OriginPolicy.any()}
     *                     is how a server says it checks nothing.
     * @return this builder.
     */
    public WsServer withOriginPolicy(final OriginPolicy originPolicy) {
        if (originPolicy == null) {
            throw new IllegalArgumentException(
                    "No origin policy: use OriginPolicy.any() to check nothing");
        }
        this.originPolicy = originPolicy;
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
     * Binds the <b>loopback</b>, which is where {@link NettyServerBuilder#DEFAULT_HOST} leaves a server
     * nobody opened up. {@link #start(String, int)} is the one which is reachable from anywhere else.
     *
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final int port) throws InterruptedException {
        return start(new NettyServerBuilder().port(port));
    }

    /**
     * The same on an interface of your own: the address of the network this server belongs on, or
     * {@link NettyServerBuilder#ANY_HOST} for every interface. Naming one is how a server becomes reachable
     * at all - the default is the loopback.
     *
     * @param host to bind, or {@link NettyServerBuilder#ANY_HOST} for every interface.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final String host,
                             final int port) throws InterruptedException {
        return start(new NettyServerBuilder().host(host).port(port));
    }

    /**
     * Where the two builders meet: this one is everything above the socket, the bootstrap is the socket -
     * the transport, the thread counts, the water marks a fan-out is paced by, the channel options - and
     * this hands the pipeline assembled here to it and binds.
     *
     * @param bootstrap to start on; its child handler is set here, so anything it already had is replaced.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final NettyServerBuilder bootstrap) throws InterruptedException {
        return bootstrap.childHandler(pipeline()).start();
    }

    private void initPipeline(final ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(maxInitialLineLength)
                .setMaxHeaderSize(maxHeaderSize)));
        pipeline.addLast(new HttpObjectAggregator(maxContentLength, true));

        if (requestDeadlineMs > 0) {
            // behind the aggregator, which is the only place the rule can be expressed: what it tells apart
            // is bytes which became a message from bytes which did not, and in front of a decoder every read
            // looks the same. It stays for the life of the connection - after the handshake the messages it
            // waits for are frames
            pipeline.addLast(new RequestDeadlineHandler(requestDeadlineMs));
        }

        if (responseDeadlineMs > 0) {
            // in front of everything which writes, and behind the codec, so what it counts is the payload
            // rather than the frame put around it
            pipeline.addLast(new ResponseDeadlineHandler(responseDeadlineMs));
        }

        // in front of everything which answers, so that nothing behind it has to ask whether the request it
        // was given is a real one: what the codec refused arrives as a substitute request, which no
        // handshake handler would recognise and no handler here would answer honestly
        pipeline.addLast(new DecoderFailureHandler());

        // ahead of everything which would do work for this request: a refused handshake should cost no more
        // than it has already cost to read. Always there - there is no policy which checks nothing, only
        // OriginPolicy.any(), which says so - and it takes itself out once a handshake has passed
        pipeline.addLast(new OriginCheckHandler(api.websocketPath(), originPolicy, channelErrorHandler));

        if (compression) {
            pipeline.addLast(new WebSocketServerCompressionHandler(0));
        }

        // the frame size is the pipeline's to say, and so is whether extensions may be negotiated - which
        // is not a choice but a consequence of the handler above being there or not
        pipeline.addLast(new WsApiHandler(api, channelErrorHandler, maxFramePayloadLength, compression));

        for (int i = 0; i < handlers.size(); i++) {
            pipeline.addLast(handlers.get(i).get());
        }

        // last, so everything added above answers first. Without it a request which is not the handshake
        // and which nobody took would sit at the end of the pipeline, discarded in silence, holding a
        // connection which nothing is on a timer to close
        pipeline.addLast(new HandshakeOnlyHandler(channelErrorHandler));
    }
}
