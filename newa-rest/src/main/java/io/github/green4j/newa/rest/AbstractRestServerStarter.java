package io.github.green4j.newa.rest;

import io.netty.channel.ChannelFuture;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractRestServerStarter {
    protected final String apiName;
    protected final int apiVersion;
    protected final String componentName;
    protected final String componentBuildVersion;
    protected final String localIfc;
    protected final int port;

    private Switch aSwitch;

    protected AbstractRestServerStarter(final String apiName,
                                        final int apiVersion,
                                        final String componentName,
                                        final String componentBuildVersion,
                                        final String localIfc,
                                        final int port) {
        this.apiName = apiName;
        this.apiVersion = apiVersion;
        this.componentName = componentName;
        this.componentBuildVersion = componentBuildVersion;
        this.localIfc = localIfc;
        this.port = port;
    }

    @SuppressWarnings("try")
    public final void start() {
        if (aSwitch != null) {
            throw new IllegalStateException("Already started");
        }

        final AtomicBoolean srvClosed = new AtomicBoolean();

        try {
            final RestApiServer.Builder srvBuilder = RestApiServer.builder();
            srvBuilder.withName(componentName)
                    .withLocalIfc(localIfc)
                    .withPort(port);

            tuneUpRestServer(srvBuilder);

            try (RestApiServer srv = srvBuilder.build()) {
                aSwitch = (cause) -> {
                    if (!srvClosed.compareAndExchange(false, true)) {
                        onServerStoppingProbablyConcurrently(cause);
                        srv.close();
                    }
                };

                Runtime.getRuntime().addShutdownHook(
                        new Thread(() ->
                                aSwitch.off("Process termination happened"))
                );

                final RestApi.Builder apiBuilder = RestApi.builder(
                        apiName,
                        apiVersion,
                        componentName,
                        componentBuildVersion);

                try {
                    final RestApi restApi = buildRestApi(apiBuilder);
                    final ChannelFuture closeFuture = srv.start(restApi);
                    onServerStarted();
                    closeFuture.sync();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            try {
                release();
            } finally {
                onServerStopped();
            }
        }
    }

    public final Switch aSwitch() {
        return aSwitch;
    }

    protected void release() {
    }

    protected void tuneUpRestServer(final RestApiServer.Builder builder) {
    }

    protected void onServerStarted() {
    }

    // The only method which can be called from another thread
    protected void onServerStoppingProbablyConcurrently(final String cause) {
    }

    protected void onServerStopped() {
    }

    protected abstract RestApi buildRestApi(RestApi.Builder builder);
}
