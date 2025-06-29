package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Scheduler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class WsApiServer implements
        ClientSessionsStatistics,
        AutoCloseable {

    private static final boolean USE_EPOLL = Epoll.isAvailable();

    public static Builder builder(final String localIfc,
                                  final int port) {
        return new Builder()
                .withLocalIfc(localIfc)
                .withPort(port);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiName;

        private int apiVersion;

        private String localIfc;
        private int port;
        private boolean useSsl;

        private int maxRequestContentLength = 64 * 1024;

        private int numberOfBosses = 1;
        private int numberOfWorkers = 1;
        private int soBacklog = 512;

        private boolean withCompression;

        private String pathPrefix = "websocket";

        private ChannelErrorHandler channelErrorHandler = (channel, cause) -> {
            System.err.println("Unexpected error in the channel '" + channel.id() + "': " + cause.getMessage());
            cause.printStackTrace(System.err);
        };

        private Builder() {
        }

        public Builder withApiName(final String apiName) {
            this.apiName = apiName;
            return this;
        }

        public Builder withApiVersion(final int apiVersion) {
            this.apiVersion = apiVersion;
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

        public Builder withPathPrefix(final String pathPrefix) {
            this.pathPrefix = pathPrefix;
            return this;
        }

        public Builder withChannelErrorHandler(final ChannelErrorHandler channelErrorHandler) {
            this.channelErrorHandler = channelErrorHandler;
            return this;
        }

        public WsApiServer build() {
            return new WsApiServer(this);
        }

        private String rootPath() {
            final StringBuilder result = new StringBuilder("/v").append(apiVersion);
            if (pathPrefix != null) {
                final String pp = pathPrefix.trim();
                if (!pp.isEmpty()) {
                    result.insert(0, pp);
                    if (!pp.startsWith("/")) {
                        result.insert(0, "/");
                    }
                }
            }
            return result.toString();
        }
    }

    private final Builder parameters;

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private volatile ClientSessions sessionManager;

    private WsApiServer(final Builder parameters) {
        this.parameters = parameters;

        final ThreadFactory bossThreadFactory = new DefaultThreadFactory(
                "Boss of " + (parameters.apiName != null
                        ? parameters.apiName :
                        getClass().getSimpleName())
        );

        bossGroup = USE_EPOLL
                ? new EpollEventLoopGroup(parameters.numberOfBosses, bossThreadFactory) :
                new NioEventLoopGroup(parameters.numberOfBosses, bossThreadFactory);

        final ThreadFactory workerThreadFactory = new DefaultThreadFactory(
                "Worker of " + (parameters.apiName != null
                        ? parameters.apiName :
                        getClass().getSimpleName())
        );

        workerGroup = USE_EPOLL
                ? new EpollEventLoopGroup(parameters.numberOfWorkers, workerThreadFactory) :
                new NioEventLoopGroup(parameters.numberOfWorkers, workerThreadFactory);
    }

    public Scheduler scheduler() {
        return (work, initialDelayMillis, delayMillis) -> {
            final ScheduledFuture<?> future = workerGroup.scheduleWithFixedDelay(work,
                    initialDelayMillis,
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            return () -> future.cancel(true);
        };
    }

    public ChannelFuture start(final Channels channels) throws Exception {
        return start(channels, null);
    }

    public ChannelFuture start(final Channels channels,
                               final WsApiServerListener listener) throws Exception {
        final SslContext sslCtx;
        if (parameters.useSsl) {
            final SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        final SendingResult sendingResult = new SendingResult() {
            @Override
            public void onSuccess(final ClientSession session) {
            }

            @Override
            public void onBackPressure(final ClientSession session) {
                if (listener != null) {
                    listener.onBackPressure(session);
                }
            }

            @Override
            public void onError(final ClientSession session,
                                final Throwable error) {
                session.close();
            }
        };

        sessionManager = new ClientSessions(new ClientSessionsListener() {
            @Override
            public void onSessionOpened(final ClientSession session) {
                if (listener != null) {
                    listener.onSessionOpened(session);
                }
            }

            @Override
            public void onSessionClosed(final ClientSession session) {
                channels.unsubscribeAll(session);
                if (listener != null) {
                    listener.onSessionClosed(session);
                }
            }
        });

        final ServerBootstrap bootstrap = new ServerBootstrap();

        final WsApiServerInitializer serverInit = new WsApiServerInitializer(
                parameters.rootPath(),
                sslCtx,
                parameters.withCompression,
                parameters.maxRequestContentLength,
                sessionManager,
                sendingResult,
                channels,
                parameters.channelErrorHandler);

        bootstrap.group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, parameters.soBacklog)
                .channel(USE_EPOLL ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(serverInit);

        final ChannelFuture bindFuture;

        if (parameters.localIfc == null || parameters.localIfc.isBlank()) {
            bindFuture = bootstrap.bind(parameters.port);
        } else {
            bindFuture = bootstrap.bind(InetAddress.getByName(parameters.localIfc), parameters.port);
        }

        final io.netty.channel.Channel channel = bindFuture.sync().channel();

        channels.start();

        return channel.closeFuture();
    }

    @Override
    public int numberOfSessions() {
        final ClientSessions sessionMgr = sessionManager;
        if (sessionMgr == null) {
            return 0;
        }
        return sessionMgr.numberOfSessions();
    }

    public int numberOfSessionsTotal() {
        final ClientSessions sessionMgr = sessionManager;
        if (sessionMgr == null) {
            return 0;
        }
        return sessionMgr.numberOfSessionsTotal();
    }

    @Override
    public void close() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
