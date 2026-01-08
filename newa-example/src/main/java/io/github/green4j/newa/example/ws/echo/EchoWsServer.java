package io.github.green4j.newa.example.ws.echo;

import io.github.green4j.newa.example.ws.StdOutWsApiListener;
import io.github.green4j.newa.lang.Work;
import io.github.green4j.newa.lang.Worker;
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.SimpleWsApiBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

import java.net.InetAddress;

public class EchoWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Worker worker = new Worker();

        final SimpleWsApiBuilder apiBuilder = new SimpleWsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withPingIntervalMs(10_000)
                .withListener(new StdOutWsApiListener());

        final WsApi api = apiBuilder.build();

        final Receiver receiver = (session, message) -> {
            System.out.printf(
                    "Received: '%s' from: %s%n",
                    message.toString(),
                    session.toString()
            );
            session.send(message);
        };

        final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        final EventLoopGroup workerGroup = new NioEventLoopGroup(1);

        final ServerBootstrap bootstrap = new ServerBootstrap();

        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(
                                new HttpObjectAggregator(
                                        65536,
                                true
                                )
                        );
                        pipeline.addLast(new WebSocketServerCompressionHandler(0));

                        pipeline.addLast(
                                new WsApiHandler(
                                        api,
                                        receiver,
                                        (channel, cause) -> {
                                            System.err.printf(
                                                    "An error %s in the channel: %s%n",
                                                    cause.getMessage(),
                                                    channel.toString());
                                            cause.printStackTrace(System.err);
                                        }
                                )
                        );
                    }
                });

        worker.doWork(new Work() {
            @Override
            public ChannelFuture doWork() throws Exception {
                final ChannelFuture bindFuture = bootstrap.bind(
                                InetAddress.getByName(LOCAL_IFC),
                                PORT
                        )
                        .sync();

                System.out.printf(
                        "Server started and listening on %s. Websocket path: %s%s%n",
                        LOCAL_SERVER_ADDRESS,
                        LOCAL_SERVER_ADDRESS,
                        api.websocketPath()
                );

                return bindFuture
                        .channel()
                        .closeFuture();
            }

            @Override
            public void close() {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        });

        System.out.println("Server stopped"); // newer reached until worker.stopper().stop(...) is called
    }
}
