/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.ws.pipeline;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.server.DecoderFailureHandler;
import io.github.green4j.newa.server.RequestDeadlineHandler;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.github.green4j.newa.websocket.HandshakeOnlyHandler;
import io.github.green4j.newa.websocket.OriginCheckHandler;
import io.github.green4j.newa.websocket.OriginPolicy;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A websocket and an HTTP api on one port, with the bootstrap and the pipeline written out by hand.
 * <pre>
 * wscat -c ws://127.0.0.1:9014/ws/v1       # a tick a second
 * curl -sD- http://127.0.0.1:9014/v1/stats  # how many have gone out, on the same port
 * </pre>
 * The composition itself needs no hand assembly - {@code WsServer.of(api).withHandler(() -> new
 * RestApiHandler(...))} produces this very pipeline, and that is the way to write it. What is here instead
 * is the reason to assemble by hand at all: <b>the write water marks are computed from what this server is
 * expected to send</b>, rather than left at a default that knows nothing about the fan-out. That is the
 * shape {@code newa-performance} uses, and it is not something a helper can guess.
 * <p>
 * The order is what makes one port serve both: {@link WsApiHandler} passes on a request whose uri is not
 * its handshake, and {@link RestApiHandler} answers everything that reaches it. Reverse the two and the
 * rest api would answer the handshake with a 404.
 * <p>
 * The rest of it is what a server facing the open internet is assembled like, written out here because a
 * hand-assembled pipeline gets none of it for free:
 * <ul>
 *   <li>a {@link RequestDeadlineHandler} and a {@link ResponseDeadlineHandler}, which
 *       {@code WsServer.withRequestDeadlineMs} and {@code withResponseDeadlineMs} install by default and a
 *       hand-assembled pipeline gets only by saying so. The first guards the window before a session exists
 *       - the ping interval and the read timeout are a <i>session's</i>, and a connection which opens and
 *       says nothing never reaches one - and goes on judging half-arrived frames afterwards; the second
 *       judges a peer which has stopped taking what is written to it;</li>
 *   <li>an {@link HttpServerCodec} with its limits written out rather than inherited - the same two numbers
 *       {@code WsServer.withMaxInitialLineLength} and {@code withMaxHeaderSize} reach, which are Netty's own
 *       defaults; the point is that on this side of the fence they are a decision;</li>
 *   <li>a {@link DecoderFailureHandler}, so that a request line or a header block past those limits is
 *       answered {@code 414} or {@code 431}. Without one, what the codec refused arrives as the substitute
 *       request the decoder emits, which no handshake handler recognises and nothing here answers
 *       honestly;</li>
 *   <li>an {@link OriginCheckHandler}, because the same-origin policy does not cover a handshake: a page on
 *       any site can open one here, and the browser will send the cookies of <b>this</b> origin with it.
 *       {@code WsServer} puts one in by itself, checking {@code OriginPolicy.sameOrigin()}, and a pipeline
 *       written out like this one gets no check at all until it says so - which is the whole reason this
 *       line is here rather than left out;</li>
 *   <li>a frame size said out loud on {@link WsApiHandler}, and extensions refused - which is not a
 *       preference but the truth about this pipeline, which has nothing in it that could inflate a frame;
 *   </li>
 *   <li>a {@link HandshakeOnlyHandler} last, so a request neither the handshake nor the stats api took
 *       closes its connection instead of sitting at the end of the pipeline holding it open.</li>
 * </ul>
 */
public class PipelineWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9014;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    private static final int MAX_REQUEST_BYTES = 8192;

    private static final int MAX_INITIAL_LINE_BYTES = 2048; // the request line of a handshake is short
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_FRAME_BYTES = 16 * 1024; // nothing here sends anything near it

    private static final int DEADLINE_SECONDS = 30;

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    private static final int TICK_BYTES = 64;
    private static final int TICKS_PER_SECOND = 1;
    private static final int LAG_SECONDS = 10; // how far behind a subscriber may fall before it is closed

    private static final AtomicLong TICKS_SENT = new AtomicLong();

    /**
     * What a session may have outstanding before its channel reports itself unwritable: everything this
     * server will send it over the seconds it is allowed to lag. Left at a default, a fan-out either
     * disconnects a subscriber which was merely slow, or lets one buy an unbounded amount of memory.
     *
     * @return the water marks of every accepted channel.
     */
    private static WriteBufferWaterMark waterMarks() {
        final int high = TICK_BYTES * TICKS_PER_SECOND * LAG_SECONDS;
        return new WriteBufferWaterMark(high / 2, high);
    }

    public static void main(final String[] args) throws Exception {
        final WsApi api = new WsApiBuilder(API_VERSION)
                .withPathPrefix("ws")
                .build(); // nothing is received here: this one only ever publishes

        final RestApi stats = buildStatsApi();

        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMarks())
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), api, stats);
                    }
                });

        // the bind belongs inside run(): the server is owned from the instant it can serve, which is what
        // leaves no window between it accepting a request and something being able to end it. What
        // WsServer would have returned is a NettyServer; assembled by hand it is whatever closes what was
        // opened here, and AutoCloseable is a functional interface, so that is one more lambda.
        new Life().run(() -> {
            final Channel serverChannel = bootstrap.bind(
                    InetAddress.getByName(LOCAL_IFC), PORT).sync().channel();

            System.out.printf("Server started and listening on %s%s. Try:%n",
                    LOCAL_SERVER_ADDRESS, api.websocketPath());
            System.out.printf("  wscat -c %s%s          -> a tick a second%n",
                    LOCAL_SERVER_ADDRESS, api.websocketPath());
            System.out.printf("  curl -sD- http://%s:%d/v1/stats   -> how many have gone out, same port%n",
                    LOCAL_IFC, PORT);

            workerGroup.scheduleWithFixedDelay(
                    () -> api.broadcastText("tick " + TICKS_SENT.incrementAndGet()),
                    1,
                    1,
                    TimeUnit.SECONDS
            );

            return () -> {
                serverChannel.close().awaitUninterruptibly();
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            };
        });

        System.out.println("Server stopped");
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final WsApi api,
                                     final RestApi stats) {
        pipeline.addLast(new HttpServerCodec(MAX_INITIAL_LINE_BYTES, MAX_HEADER_BYTES, MAX_REQUEST_BYTES));
        pipeline.addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));

        // behind the aggregator, which is where both of them belong: what the first tells apart is bytes
        // which became a message from bytes which did not, and in front of a decoder every read looks the
        // same. Before the handshake there is no session, so no ping interval and no read timeout, and this
        // is the only thing which would ever close a connection which opens and says nothing
        pipeline.addLast(new RequestDeadlineHandler(DEADLINE_SECONDS * 1000L));

        // and the other end of it: a peer which has been written to and is not taking it. Nothing is timed
        // while nothing is owed, so a session with nothing to broadcast is on no clock at all
        pipeline.addLast(new ResponseDeadlineHandler(DEADLINE_SECONDS * 1000L));

        // what the codec above refused arrives here as a substitute request, and the decoder has already
        // stopped reading this connection. This answers it 414 or 431 and closes it, which is what WsServer
        // does by itself
        pipeline.addLast(new DecoderFailureHandler());

        // one for all of them: whichever catches a failed channel closes it and reports it exactly once
        final ChannelErrorHandler channelErrors = (channel, cause) -> System.err.printf(
                "An error %s in the channel: %s%n", cause.getMessage(), channel);

        // ahead of everything which would do work for the request: a refused handshake should cost no more
        // than it has already cost to read. Only the handshake is judged, so the stats api below is not.
        // Named here because a hand-assembled pipeline has no default: WsServer would check sameOrigin()
        pipeline.addLast(new OriginCheckHandler(
                api.websocketPath(), OriginPolicy.allowing(ALLOWED_ORIGIN), channelErrors));

        // whatever was not the websocket path carries on to the api handler behind this one: the handshake
        // handler forwards a request whose uri it does not recognise, and it only ever consumes frames.
        // Extensions are refused because nothing here negotiates one - say true without a
        // WebSocketServerCompressionHandler in the pipeline and the decoder accepts frames with the
        // reserved bits set and hands their payload on uninflated
        pipeline.addLast(new WsApiHandler(api, channelErrors, MAX_FRAME_BYTES, false));

        pipeline.addLast(new RestApiHandler(stats, new JsonErrorHandler(), channelErrors));

        // last, so a request neither of them took closes its connection rather than being discarded in
        // silence while the socket stays open
        pipeline.addLast(new HandshakeOnlyHandler(channelErrors));
    }

    private static RestApi buildStatsApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                "Stats API", "What the websocket has sent", API_VERSION, "0.0.1");

        apiBuilder.getJson("/stats", (context, output) -> output.numberValue(TICKS_SENT.get()))
                .withDescription("How many ticks have been broadcast since the server started.");

        return apiBuilder.build();
    }
}
