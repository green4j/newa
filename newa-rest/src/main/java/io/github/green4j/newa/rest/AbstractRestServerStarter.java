package io.github.green4j.newa.rest;

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
            try (RestApiServer srv = RestApiServer.newServer(
                    componentName, localIfc, port)) {
                aSwitch = (cause) -> {
                    if (!srvClosed.compareAndExchange(false, true)) {
                        System.out.println(cause + ". Server is stopping...");
                        srv.close(); // try
                    }
                };

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    aSwitch.off("Process termination happened");
                }));

                final RestApi.Builder apiBuilder = RestApi.builder(
                        apiName,
                        apiVersion,
                        componentName,
                        componentBuildVersion);

                try {
                    srv.start(buildRestApi(apiBuilder)).sync();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            try {
                release();
            } finally {
                System.out.println("Server stopped.");
            }
        }
    }

    public final Switch aSwitch() {
        return aSwitch;
    }

    public void release() {
    }

    protected abstract RestApi buildRestApi(RestApi.Builder builder);
}
