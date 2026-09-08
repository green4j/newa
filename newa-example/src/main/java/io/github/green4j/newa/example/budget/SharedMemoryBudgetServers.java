/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.budget;

import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.files.FileServer;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One REST, file and WebSocket server drawing differently sized connections from one process-wide memory
 * budget. Spare capacity follows traffic between the three ports; each port's {@code maxConnections}
 * remains an independent fairness ceiling.
 * <pre>
 * curl -sD- http://127.0.0.1:9009/v1/hello
 * curl -sD- http://127.0.0.1:9010/budget.txt
 * wscat -c ws://127.0.0.1:9011/ws/v1       # type anything: it comes back
 * </pre>
 * {@code rest.pair.PairedRestServers} is this example without the budget, and the difference is the whole
 * point of this one. There, {@link Life#all} is the only thing the two servers share: each is given a fixed
 * {@code maxConnections}, so the memory a quiet port is entitled to sits unused while a busy one refuses.
 * Here the three of them register with one {@link ServerMemoryBudget}, each connection reserves the estimate
 * derived from that server's own settings - the largest response a REST handler renders, the chunk a file is
 * pumped in, the frame a session may be sent - and returns it when it closes. What is not guaranteed by a
 * floor follows the traffic. The ceilings stay, and mean what they always meant: not memory, but how much of
 * one port a single kind of client may take.
 */
public class SharedMemoryBudgetServers {
    public static final String API_NAME = "Budget REST API";
    public static final String API_DESCRIPTION = "Shared memory budget example";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int REST_PORT = 9009;
    public static final int FILE_PORT = 9010;
    public static final int WEBSOCKET_PORT = 9011;

    public static final String REST_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, REST_PORT);
    public static final String FILE_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, FILE_PORT);
    public static final String WEBSOCKET_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, WEBSOCKET_PORT);

    private static final int HEAP_PERCENTAGE = 70;
    private static final int DIRECT_MEMORY_PERCENTAGE = 70;

    /** Outbound, and per connection: what each server may be holding for one peer at a time. */
    private static final int REST_RESPONSE_BYTES = 64 * 1024;
    private static final int FILE_CHUNK_BYTES = 32 * 1024;
    private static final int WS_FRAME_BYTES = 64 * 1024;

    /** Inbound, and nothing to do with the estimates above: the largest frame a session may send. */
    private static final int MAX_FRAME_PAYLOAD_BYTES = 256 * 1024;

    private static final int REST_MIN_CONNECTIONS = 10;
    private static final int REST_MAX_CONNECTIONS = 500;
    private static final int FILE_MIN_CONNECTIONS = 0;
    private static final int FILE_MAX_CONNECTIONS = 500;
    private static final int WEBSOCKET_MIN_CONNECTIONS = 0;
    private static final int WEBSOCKET_MAX_CONNECTIONS = 5_000;

    public static void main(final String[] args) throws Exception {
        final RestApi restApi = buildRestApi();
        final FileSet files = buildFileSet();
        final WsApi wsApi = buildWsApi();

        // one for the process, and the only thing the three servers below share
        final ServerMemoryBudget memory = buildBudget();

        new Life().run(
                Life.all(
                        () -> RestServer.of(restApi)
                                .withCompression()
                                .withMemoryBudget(memory, REST_RESPONSE_BYTES)
                                .start(bootstrap(REST_PORT, REST_MIN_CONNECTIONS, REST_MAX_CONNECTIONS)),
                        () -> FileServer.of(files)
                                .withChunkSize(FILE_CHUNK_BYTES)
                                .withMemoryBudget(memory)
                                .start(bootstrap(FILE_PORT, FILE_MIN_CONNECTIONS, FILE_MAX_CONNECTIONS)),
                        () -> WsServer.of(wsApi)
                                .withCompression()
                                // inbound, inflated included
                                .withMaxFramePayloadLength(MAX_FRAME_PAYLOAD_BYTES)
                                .withMemoryBudget(memory, WS_FRAME_BYTES) // outbound estimate
                                .start(bootstrap(
                                        WEBSOCKET_PORT,
                                        WEBSOCKET_MIN_CONNECTIONS,
                                        WEBSOCKET_MAX_CONNECTIONS))
                ),
                new Life.Observer() {
                    @Override
                    public void onRunning() {
                        // all three are open and this thread is about to park, which is the moment to say so
                        printUsage(wsApi);
                    }

                    @Override
                    public void onEnding(final String cause) {
                        System.out.println("Ending: " + cause);
                    }
                }
        );

        System.out.println("Servers stopped");
    }

    private static RestApi buildRestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        builder.getJson("/hello", (context, output) -> output.stringValue("hello"));

        return builder.build();
    }

    private static FileSet buildFileSet() throws IOException {
        final Path file = Files.createTempFile("newa-budget-example-", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "served by the budgeted file server");

        return FileSet.builder().file("/budget.txt", file).build();
    }

    private static WsApi buildWsApi() {
        return new WsApiBuilder(API_VERSION)
                .withPathPrefix("ws")
                .withTextReceiver((session, message, last) ->
                        session.sendText(message))
                .build();
    }

    private static ServerMemoryBudget buildBudget() {
        return ServerMemoryBudget.builder()
                .heapPercentage(HEAP_PERCENTAGE)
                .directMemoryPercentage(DIRECT_MEMORY_PERCENTAGE)
                .observer(new ServerMemoryBudget.Observer() {
                    @Override
                    public void onConnectionRefused(final ServerMemoryBudget.Event event) {
                        System.out.printf(
                                "Memory admission refused on %s: %s, heap=%d/%d, direct=%d/%d%n",
                                event.registration().snapshot().name(),
                                event.refusalReason(),
                                event.reservedHeapBytes(),
                                event.heapCapacityBytes(),
                                event.reservedDirectMemoryBytes(),
                                event.directMemoryCapacityBytes()
                        );
                    }
                })
                .build();
    }

    private static NettyServerBuilder bootstrap(final int port,
                                                final int minConnections,
                                                final int maxConnections) {
        return new NettyServerBuilder()
                .host(LOCAL_IFC)
                .port(port)
                .workerThreads(Math.max(
                        1,
                        Runtime.getRuntime().availableProcessors() / 3
                ))
                // Both bounds are optional: zero leaves that floor or ceiling unset.
                .minConnections(minConnections)
                .maxConnections(maxConnections);
    }

    private static void printUsage(final WsApi wsApi) {
        System.out.printf("Three servers on one memory budget: %s, %s and %s. Try:%n",
                REST_ADDRESS, FILE_ADDRESS, WEBSOCKET_ADDRESS);
        System.out.printf("  curl -s %s/v1/hello        -> the REST port, 64K reserved per connection%n",
                REST_ADDRESS);
        System.out.printf("  curl -sD- %s/budget.txt    -> the file port, a 32K chunk per connection%n",
                FILE_ADDRESS);
        System.out.printf("  wscat -c %s%s            -> the websocket port, and it echoes%n",
                WEBSOCKET_ADDRESS, wsApi.websocketPath());
    }
}
