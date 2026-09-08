/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget.harness;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.files.FileServer;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.text.LineAppendable;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;
import io.netty.channel.Channel;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The server under test of the memory budget integration tests: a REST, a file and a WebSocket server
 * drawing from one {@link ServerMemoryBudget}, next to an admin server which is deliberately outside it and
 * so still answers when the budget is full.
 *
 * <p>Everything is an environment variable because one image serves every case: the same classpath runs
 * budgeted and unbudgeted, under whatever heap, direct-memory and container limits the test gives its JVM.
 *
 * <p>An {@link OutOfMemoryError} anywhere halts the process rather than being reported and survived.
 * {@code -XX:+ExitOnOutOfMemoryError} covers only what the VM itself raises, and the limit which bites
 * first here is Netty's direct-memory ceiling, whose {@code OutOfDirectMemoryError} is thrown from Java and
 * arrives as a channel failure like any other. A test asking whether the budget kept the process alive has
 * to see both the same way.
 */
public final class BudgetServerHarness {
    /**
     * The container ports. Fixed rather than ephemeral: the test maps them, and the harness has no way to
     * tell anybody a port it chose itself before the wait strategy has let the test look.
     */
    public static final int REST_PORT = 8080;
    public static final int FILE_PORT = 8081;
    public static final int WEBSOCKET_PORT = 8082;
    public static final int ADMIN_PORT = 8090;

    /**
     * Printed once every server is bound. The container's wait strategy is this line.
     */
    public static final String READY_LINE = "HARNESS READY";

    /**
     * Printed immediately before the process is halted on the first out-of-memory error seen anywhere.
     */
    public static final String FATAL_LINE = "HARNESS FATAL";

    /**
     * What the process exits with when it runs out of memory, whoever noticed first. The same code
     * {@code -XX:+ExitOnOutOfMemoryError} uses, so both paths look alike from the outside.
     */
    public static final int OOM_EXIT_CODE = 3;

    public static final String HELLO_PATH = "/v1/hello";
    public static final String SINK_PATH = "/v1/sink";
    public static final String FILE_PATH = "/budget.bin";
    public static final String WEBSOCKET_PATH = "/ws/v1";
    public static final String ADMIN_PATH = "/v1/budget";

    private static final String[] SERVER_KEYS = {"rest", "file", "ws"};

    /**
     * The shape of the servers themselves, which no test varies: what the tests move is the memory the
     * process is given and the sizes the estimate is built from.
     */
    private static final int MAX_RESPONSE_SIZE = 64 * 1024;
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int FILE_BYTES = 4 * 1024 * 1024;
    private static final int WORKER_THREADS = 2;

    /**
     * The REST server's guaranteed floor. Small, and only so that there is one to watch coming back.
     */
    private static final int MIN_CONNECTIONS = 2;

    private BudgetServerHarness() {
    }

    public static void main(final String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, cause) -> haltIfOutOfMemory(cause));

        final boolean budgeted = !"off".equals(env("NEWA_BUDGET", "on"));
        final int maxContentLength = intEnv("NEWA_MAX_CONTENT_LENGTH", 1024 * 1024);
        final int maxFramePayloadLength = intEnv("NEWA_MAX_FRAME", 64 * 1024);

        final Gauges gauges = new Gauges();
        final ServerMemoryBudget budget = budgeted
                ? ServerMemoryBudget.builder()
                        .heapPercentage(intEnv("NEWA_HEAP_PERCENTAGE", 70))
                        .directMemoryPercentage(intEnv("NEWA_DIRECT_PERCENTAGE", 70))
                        .observer(gauges)
                        .build()
                : null;

        final NettyServer[] servers = new NettyServer[SERVER_KEYS.length];

        servers[0] = startRest(budget, maxContentLength);
        servers[1] = startFile(budget, maxContentLength);
        servers[2] = startWebSocket(budget, maxContentLength, maxFramePayloadLength);

        final NettyServer admin = startAdmin(budget, gauges, servers);

        System.out.println("Harness budget=" + budgeted
                + " maxContentLength=" + maxContentLength
                + " maxFramePayloadLength=" + maxFramePayloadLength
                + " maxHeapBytes=" + Runtime.getRuntime().maxMemory()
                + " maxDirectMemoryBytes=" + PlatformDependent.maxDirectMemory());
        System.out.println(READY_LINE);
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            admin.close();
            for (final NettyServer server : servers) {
                server.close();
            }
        }));

        new CountDownLatch(1).await(); // until the container is stopped, or memory ends the process
    }

    private static NettyServer startRest(final ServerMemoryBudget budget,
                                         final int maxContentLength) throws InterruptedException {
        final char[] payload = new char[MAX_RESPONSE_SIZE];
        Arrays.fill(payload, 'x');
        final String response = new String(payload);

        final RestApiBuilder api = new RestApiBuilder("budget-rest", "memory budget harness", 1, "intTest");
        api.getTxt("/hello", (context, output) -> output.append("hello"));
        // the flood's endpoint: it takes a maximum-sized body and answers with a maximum-sized response,
        // so a client which sends everything and reads nothing loads both halves of the estimate at once
        api.postTxt("/sink", (context, output) -> output.append(response));

        final RestServer server = RestServer.of(api.build())
                .withMaxContentLength(maxContentLength)
                .withChannelErrorHandler(FATAL_ON_OUT_OF_MEMORY);
        if (budget != null) {
            server.withMemoryBudget(budget, MAX_RESPONSE_SIZE);
        }
        return server.start(bootstrap(REST_PORT, budget != null));
    }

    private static NettyServer startFile(final ServerMemoryBudget budget,
                                         final int maxContentLength) throws IOException, InterruptedException {
        final FileServer server = FileServer.of(FileSet.builder().file(FILE_PATH, file()).build())
                .withMaxContentLength(maxContentLength)
                .withChannelErrorHandler(FATAL_ON_OUT_OF_MEMORY);
        server.withChunkSize(CHUNK_SIZE);
        if (budget != null) {
            server.withMemoryBudget(budget);
        }
        return server.start(bootstrap(FILE_PORT, false));
    }

    private static NettyServer startWebSocket(final ServerMemoryBudget budget,
                                              final int maxContentLength,
                                              final int maxFramePayloadLength) throws InterruptedException {
        final WsApi api = new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver((session, message, last) -> session.sendText(message))
                .build();

        final WsServer server = WsServer.of(api)
                .withMaxContentLength(maxContentLength)
                .withMaxFramePayloadLength(maxFramePayloadLength)
                .withChannelErrorHandler(FATAL_ON_OUT_OF_MEMORY);
        if (budget != null) {
            server.withMemoryBudget(budget, maxFramePayloadLength);
        }
        return server.start(bootstrap(WEBSOCKET_PORT, false));
    }

    private static NettyServer startAdmin(final ServerMemoryBudget budget,
                                          final Gauges gauges,
                                          final NettyServer[] servers) throws InterruptedException {
        final RestApiBuilder api = new RestApiBuilder("budget-admin", "memory budget gauges", 1, "intTest");
        api.getTxt("/budget", (context, output) -> report(output, budget, gauges, servers));

        // no budget of its own on purpose: what it reports is worth most exactly when the budget is full
        return RestServer.of(api.build())
                .withChannelErrorHandler(FATAL_ON_OUT_OF_MEMORY)
                .start(new NettyServerBuilder()
                        .host(NettyServerBuilder.ANY_HOST)
                        .port(ADMIN_PORT)
                        .bossThreads(1)
                        .workerThreads(1)
                        .maxConnections(8));
    }

    private static void report(final LineAppendable output,
                               final ServerMemoryBudget budget,
                               final Gauges gauges,
                               final NettyServer[] servers) {
        output.appendln("budget=" + (budget != null));
        if (budget != null) {
            final ServerMemoryBudget.Snapshot snapshot = budget.snapshot();
            line(output, "maximumHeapBytes", snapshot.maximumHeapBytes());
            line(output, "maximumDirectMemoryBytes", snapshot.maximumDirectMemoryBytes());
            line(output, "heapCapacityBytes", snapshot.heapCapacityBytes());
            line(output, "directMemoryCapacityBytes", snapshot.directMemoryCapacityBytes());
            line(output, "reservedHeapBytes", snapshot.reservedHeapBytes());
            line(output, "reservedDirectMemoryBytes", snapshot.reservedDirectMemoryBytes());
            line(output, "connections", snapshot.connections());
            line(output, "admitted", gauges.admitted.sum());
            line(output, "released", gauges.released.sum());
            line(output, "refused", gauges.refusedTotal());
            for (final ServerMemoryBudget.RefusalReason reason : ServerMemoryBudget.RefusalReason.values()) {
                line(output, "refused." + reason.name(), gauges.refused(reason));
            }
        }

        line(output, "runtimeMaxHeapBytes", Runtime.getRuntime().maxMemory());
        line(output, "usedHeapBytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        line(output, "runtimeMaxDirectMemoryBytes", PlatformDependent.maxDirectMemory());
        line(output, "usedDirectMemoryBytes", PlatformDependent.usedDirectMemory());

        for (int i = 0; i < servers.length; i++) {
            final ServerMemoryBudget.RegistrationSnapshot registration =
                    servers[i] == null ? null : servers[i].memoryRegistrationSnapshot();
            if (registration == null) {
                continue;
            }
            final String key = SERVER_KEYS[i];
            line(output, key + ".connections", registration.connections());
            line(output, key + ".minConnections", registration.minConnections());
            line(output, key + ".maxConnections", registration.maxConnections());
            line(output, key + ".heapBytesPerConnection",
                    registration.estimate().heapBytesPerConnection());
            line(output, key + ".directMemoryBytesPerConnection",
                    registration.estimate().directMemoryBytesPerConnection());
        }
    }

    private static void line(final LineAppendable output,
                             final String key,
                             final long value) {
        output.appendln(key + "=" + value);
    }

    private static NettyServerBuilder bootstrap(final int port,
                                                final boolean floor) {
        final NettyServerBuilder bootstrap = new NettyServerBuilder()
                .host(NettyServerBuilder.ANY_HOST) // a container port nobody outside could reach otherwise
                .port(port)
                .bossThreads(1)
                .workerThreads(WORKER_THREADS)
                .backlog(1024); // no connection should be refused by the accept queue rather than the budget
        if (floor) {
            // a floor is only legal under a budget, and only the REST server gets one: it is what the
            // soak test watches coming back after every connection has gone
            bootstrap.minConnections(MIN_CONNECTIONS);
        }
        return bootstrap;
    }

    private static Path file() throws IOException {
        final Path file = Files.createTempFile("newa-budget-", ".bin");
        file.toFile().deleteOnExit();
        Files.write(file, new byte[FILE_BYTES]);
        return file;
    }

    private static final ChannelErrorHandler FATAL_ON_OUT_OF_MEMORY = new ChannelErrorHandler() {
        private final ChannelErrorHandler reported = new StdErrChannelErrorHandler();

        @Override
        public void onError(final Channel channel,
                            final Throwable cause) {
            haltIfOutOfMemory(cause);
            reported.onError(channel, cause);
        }
    };

    private static void haltIfOutOfMemory(final Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof OutOfMemoryError) {
                System.out.println(FATAL_LINE + " " + current);
                System.out.flush();
                Runtime.getRuntime().halt(OOM_EXIT_CODE);
            }
        }
    }

    private static String env(final String name,
                              final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value.trim();
    }

    private static int intEnv(final String name,
                              final int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
    }

    /**
     * The budget's own view of itself: what it admitted, released and refused, and why.
     */
    private static final class Gauges implements ServerMemoryBudget.Observer {
        private final LongAdder admitted = new LongAdder();
        private final LongAdder released = new LongAdder();
        private final AtomicLongArray refused =
                new AtomicLongArray(ServerMemoryBudget.RefusalReason.values().length);

        @Override
        public void onConnectionAdmitted(final ServerMemoryBudget.Event event) {
            admitted.increment();
        }

        @Override
        public void onConnectionReleased(final ServerMemoryBudget.Event event) {
            released.increment();
        }

        @Override
        public void onConnectionRefused(final ServerMemoryBudget.Event event) {
            refused.incrementAndGet(event.refusalReason().ordinal());
        }

        private long refused(final ServerMemoryBudget.RefusalReason reason) {
            return refused.get(reason.ordinal());
        }

        private long refusedTotal() {
            long total = 0;
            for (int i = 0; i < refused.length(); i++) {
                total += refused.get(i);
            }
            return total;
        }
    }
}
