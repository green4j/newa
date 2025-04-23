package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetAddress;
import java.util.concurrent.ThreadFactory;

public final class RestApiServer implements AutoCloseable {
    private static final boolean USE_EPOLL = Epoll.isAvailable();

    public static RestApiServer newServer(final String name,
                                          final String localIfc,
                                          final int port) {
        return builder(localIfc, port)
                .withName(name)
                .withCompression()
                .build();
    }

    public static Builder builder(final String localIfc, final int port) {
        return new Builder()
                .withLocalIfc(localIfc)
                .withPort(port);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;

        private String localIfc = "127.0.0.1";
        private int port = 8080;
        private boolean useSsl;

        private int maxRequestContentLength = 64 * 1024;

        private CorsConfig corsConfig;

        private int numberOfBosses = 1;
        private int numberOfWorkers = 1;
        private int soBacklog = 512;

        private boolean withCompression;

        private ErrorHandler errorHandler = new TextErrorHandler();

        private ChannelErrorHandler channelErrorHandler = (channel, cause) -> {
            System.err.println("Unexpected error in the channel '" + channel.id() + "': " + cause.getMessage());
            cause.printStackTrace(System.err);
        };

        private Builder() {
        }

        public Builder withName(final String name) {
            this.name = name;
            return this;
        }

        public Builder withLocalIfc(final String localIfc) {
            this.localIfc = localIfc;
            return this;
        }

        public Builder withPort(final int port) {
            this.port = port;
            return this;
        }

        public Builder withSsl() {
            this.useSsl = true;
            return this;
        }

        public Builder withMaxRequestContentLength(final int maxRequestContentLength) {
            this.maxRequestContentLength = maxRequestContentLength;
            return this;
        }

        public Builder withNumberOfBosses(final int numberOfBosses) {
            this.numberOfBosses = numberOfBosses;
            return this;
        }

        public Builder withNumberOfWorkers(final int numberOfWorkers) {
            this.numberOfWorkers = numberOfWorkers;
            return this;
        }

        public Builder withSoBacklog(final int soBacklog) {
            this.soBacklog = soBacklog;
            return this;
        }

        public Builder withCompression() {
            this.withCompression = true;
            return this;
        }

        public Builder withoutCompression() {
            this.withCompression = false;
            return this;
        }

        public Builder withCorsConfig(final CorsConfig corsConfig) {
            this.corsConfig = corsConfig;
            return this;
        }

        public Builder withErrorHandler(final ErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder withChannelErrorHandler(final ChannelErrorHandler channelErrorHandler) {
            this.channelErrorHandler = channelErrorHandler;
            return this;
        }

        public RestApiServer build() {
            return new RestApiServer(this);
        }
    }

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private final Builder parameters;

    private RestApiServer(final Builder parameters) {
        this.parameters = parameters;

        final ThreadFactory bossThreadFactory = new DefaultThreadFactory(
                "Boss of " + (parameters.name != null
                        ? parameters.name :
                        getClass().getSimpleName())
        );

        bossGroup = USE_EPOLL
                ? new EpollEventLoopGroup(parameters.numberOfBosses, bossThreadFactory) :
                new NioEventLoopGroup(parameters.numberOfBosses, bossThreadFactory);

        final ThreadFactory workerThreadFactory = new DefaultThreadFactory(
                "Worker of " + (parameters.name != null
                        ? parameters.name :
                        getClass().getSimpleName())
        );

        workerGroup = USE_EPOLL
                ? new EpollEventLoopGroup(parameters.numberOfWorkers, workerThreadFactory) :
                new NioEventLoopGroup(parameters.numberOfWorkers, workerThreadFactory);
    }

    public ChannelFuture start(final RestApi restApi) throws Exception {
        final SslContext sslCtx;
        if (parameters.useSsl) {
            final SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, parameters.soBacklog)
                .channel(USE_EPOLL ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(
                        new RestApiServerInitializer(
                                sslCtx,
                                parameters.withCompression,
                                restApi,
                                parameters.errorHandler,
                                parameters.maxRequestContentLength,
                                parameters.corsConfig,
                                parameters.channelErrorHandler
                        )
                );

        final ChannelFuture bindFuture;
        final String listenTo;

        if (parameters.localIfc == null || parameters.localIfc.isBlank()) {
            bindFuture = bootstrap.bind(parameters.port);
            listenTo = "127.0.0.1:" + parameters.port;
        } else {
            bindFuture = bootstrap.bind(InetAddress.getByName(parameters.localIfc), parameters.port);
            listenTo = parameters.localIfc + ':' + parameters.port;
        }

        final Channel ch = bindFuture.sync().channel();
        final ChannelFuture close = ch.closeFuture();

        final String helpPath = restApi.helpPath();

        final String serviceDescription = restApi.description();

        System.out.println((serviceDescription != null && !serviceDescription.isBlank()
                ? serviceDescription : "REST API") + " is listening to " + listenTo + "..."
                + (helpPath != null ? " Help "
                + (parameters.useSsl ? "https://" : "http://")
                + listenTo + helpPath : ""));

        return close;
    }

    @Override
    public void close() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
