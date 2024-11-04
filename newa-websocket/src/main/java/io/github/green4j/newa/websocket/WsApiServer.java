package io.github.green4j.newa.websocket;

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

    public static Builder builder(final String localIfc, final int port) {
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
        private int numberOfWorkers = 0;

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

        public Builder withNumberOfWorkers(final int numberOfWorkers) {
            if (numberOfWorkers > 1_000) {
                throw new IllegalArgumentException("Too many workers: " + numberOfWorkers);
            }
            this.numberOfWorkers = numberOfWorkers;
            return this;
        }

        public WsApiServer build() {
            return new WsApiServer(this);
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
                ? new EpollEventLoopGroup(1, bossThreadFactory) :
                new NioEventLoopGroup(1, bossThreadFactory);

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
                //System.out.println("Slow consumer");
            }

            @Override
            public void onError(final ClientSession session, final Throwable error) {
                session.close();
            }
        };

        sessionManager = new ClientSessions(new ClientSessionsListener() {
            @Override
            public void onSessionOpened(final ClientSession session) {
            }

            @Override
            public void onSessionClosed(final ClientSession session) {
                channels.unsubscribeAll(session);
            }
        });

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .channel(USE_EPOLL ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new WsApiServerInitializer(
                        "/websocket/v" + parameters.apiVersion,
                        sslCtx,
                        sessionManager,
                        sendingResult,
                        channels)
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

        final io.netty.channel.Channel ch = bindFuture.sync().channel();

        channels.start();

        final ChannelFuture closeFuture = ch.closeFuture();

        System.out.println("WebSocket server is listening to " + listenTo + "...");

        return closeFuture;
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
