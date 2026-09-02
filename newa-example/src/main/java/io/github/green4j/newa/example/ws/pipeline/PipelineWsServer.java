package io.github.green4j.newa.example.ws.pipeline;

import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.websocket.SimpleWsApiBuilder;
import io.github.green4j.newa.websocket.WsApi;
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
 * websocat ws://127.0.0.1:9014/ws/v1        # a tick a second
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
 */
public class PipelineWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9014;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    private static final int MAX_REQUEST_BYTES = 8192;

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
        final WsApi api = new SimpleWsApiBuilder(API_VERSION)
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

            System.out.printf(
                    "Server started and listening on %s. Websocket path: %s%s, stats on http://%s:%d%s%n",
                    LOCAL_SERVER_ADDRESS,
                    LOCAL_SERVER_ADDRESS,
                    api.websocketPath(),
                    LOCAL_IFC,
                    PORT,
                    "/v1/stats"
            );

            workerGroup.scheduleWithFixedDelay(
                    () -> api.broadcast("tick " + TICKS_SENT.incrementAndGet()),
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
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));

        // whatever was not the websocket path carries on to the api handler behind this one: the handshake
        // handler forwards a request whose uri it does not recognise, and it only ever consumes frames
        pipeline.addLast(new WsApiHandler(api, (channel, cause) -> System.err.printf(
                "An error %s in the channel: %s%n", cause.getMessage(), channel)));

        pipeline.addLast(new RestApiHandler(stats, new JsonErrorHandler(),
                (channel, cause) -> System.err.printf(
                        "An error %s in the channel: %s%n", cause.getMessage(), channel)));
    }

    private static RestApi buildStatsApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                "Stats API", "What the websocket has sent", API_VERSION, "0.0.1");

        apiBuilder.getJson("/stats", (context, output) -> output.numberValue(TICKS_SENT.get()))
                .withDescription("How many ticks have been broadcast since the server started.");

        return apiBuilder.build();
    }
}
