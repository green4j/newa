package io.github.green4j.newa.example.rest.hello;

import io.github.green4j.newa.lang.Work;
import io.github.green4j.newa.lang.Worker;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.handles.Json_Execute;
import io.github.green4j.newa.rest.handles.Json_Help;
import io.github.green4j.newa.rest.handles.Json_JvmInfo;
import io.github.green4j.newa.rest.handles.Json_JvmThreadDump;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetAddress;

public class HelloRestServer {
    public static final String API_NAME = "Hello API";
    public static final String API_DESCRIPTION = "My Hello API Server";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9009;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Worker worker = new Worker();

        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        apiBuilder.getJson("/hello/{name}",
                (request,
                 pathParameters,
                 output) ->
                        output.stringValue(
                                String.format(
                                        "Hello %s!",
                                        pathParameters.parameterValueRequired("name")
                                )
                        )
        ).withPathParameterDescriptions("name - Your name");
        apiBuilder.getJson("/jvm/info", new Json_JvmInfo());
        apiBuilder.getJson("/jvm/threads", new Json_JvmThreadDump());
        apiBuilder.postJson(
                "/shutdown",
                new Json_Execute(
                        () -> worker
                                .stopper()
                                .stop("Called by REST API")
                )
        );

        // API version to publish without path's prefix,
        // directly on the root
        apiBuilder
                .root()
                .getJson(
                        "/version",
                        (request,
                         pathParameters,
                         output) ->
                                output.stringValue(apiBuilder.fullVersion()));

        final RestApi api = apiBuilder.buildWithHelp(Json_Help.factory());

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
                        pipeline.addLast(new HttpContentCompressor());
                        pipeline.addLast(new HttpObjectAggregator(
                                65536,
                                true
                        ));
                        pipeline.addLast(
                                new RestApiHandler(
                                        api,
                                        new JsonErrorHandler(),
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
                        "Server started and listening on %s. Help is available on %s%s%n",
                        LOCAL_SERVER_ADDRESS,
                        LOCAL_SERVER_ADDRESS,
                        api.helpPath()
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

        System.out.println("Server stopped");
    }
}
