/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa;

import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.StaticRestHandler;
import io.github.green4j.newa.rest.files.FileServer;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

class MemoryBudgetServersTest {
    private static final String HOST = "127.0.0.1";

    @TempDir
    Path directory;

    @Test
    void restFileAndWebSocketServersDrawFromOneDynamicBudget() throws Exception {
        final Path file = directory.resolve("file.txt");
        Files.writeString(file, "file");

        final AtomicInteger refusals = new AtomicInteger();
        final ServerMemoryBudget budget = ServerMemoryBudget.builder()
                .heapPercentage(1)
                .directMemoryPercentage(1)
                .observer(new ServerMemoryBudget.Observer() {
                    @Override
                    public void onConnectionRefused(final ServerMemoryBudget.Event event) {
                        refusals.incrementAndGet();
                    }
                })
                .build();
        final ServerMemoryBudget.Snapshot capacity = budget.snapshot();
        final long sharedCost = Math.min(
                capacity.heapCapacityBytes(),
                capacity.directMemoryCapacityBytes()
        ) / 3;
        Assertions.assertTrue(sharedCost > 256 * 1024,
                "The test JVM memory budget is too small for the integration test");

        final RestApiBuilder restApiBuilder =
                new RestApiBuilder("memory-rest", "memory budget test", 1, "test");
        restApiBuilder.get("/hello", StaticRestHandler.txt("hello"));
        final RestApi restApi = restApiBuilder.build();

        final FileSet files = FileSet.builder().file("/file", file).build();
        final WsApi wsApi = new WsApiBuilder(1).build();

        try (NettyServer rest = RestServer.of(restApi)
                .withMaxContentLength(1024)
                .withAdditionalMemoryEstimate(sharedCost, sharedCost)
                .withMemoryBudget(budget, 1024)
                .start(bootstrap());
                NettyServer fileServer = FileServer.of(files)
                        .withMaxContentLength(1024)
                        .withChunkSize(1024)
                        .withAdditionalMemoryEstimate(sharedCost, sharedCost)
                        .withMemoryBudget(budget)
                        .start(bootstrap());
                NettyServer websocket = WsServer.of(wsApi)
                        .withMaxContentLength(1024)
                        .withMaxFramePayloadLength(1024)
                        .withAdditionalMemoryEstimate(sharedCost, sharedCost)
                        .withMemoryBudget(budget, 1024)
                        .start(bootstrap())) {

            Assertions.assertEquals(
                    sharedCost + 25_600,
                    rest.memoryRegistrationSnapshot().estimate().heapBytesPerConnection()
            );
            Assertions.assertEquals(
                    sharedCost + 69_632,
                    rest.memoryRegistrationSnapshot().estimate().directMemoryBytesPerConnection()
            );
            Assertions.assertEquals(
                    sharedCost + 6_144,
                    fileServer.memoryRegistrationSnapshot().estimate()
                            .directMemoryBytesPerConnection()
            );
            Assertions.assertEquals(
                    // two request heads, not one: the exchange gate lets a connection hold the request
                    // behind the one being answered, and the handshake is a request like any other
                    sharedCost + 24_576,
                    websocket.memoryRegistrationSnapshot().estimate().heapBytesPerConnection()
            );
            Assertions.assertEquals(
                    sharedCost + 4_096,
                    websocket.memoryRegistrationSnapshot().estimate()
                            .directMemoryBytesPerConnection()
            );

            try (Socket restConnection = new Socket(HOST, rest.port());
                    Socket fileConnection = new Socket(HOST, fileServer.port())) {
                await(() -> budget.snapshot().connections() == 2);

                try (Socket refused = new Socket(HOST, websocket.port())) {
                    await(() -> refusals.get() == 1);
                    Assertions.assertEquals(2, budget.snapshot().connections());
                }

                restConnection.close();
                await(() -> budget.snapshot().connections() == 1);

                try (Socket admitted = new Socket(HOST, websocket.port())) {
                    await(() -> budget.snapshot().connections() == 2);
                    Assertions.assertTrue(admitted.isConnected());
                }
            }

            await(() -> budget.snapshot().connections() == 0);
        }
    }

    @Test
    void churnAcrossThreeServersLeavesNoReservationBehind() throws Exception {
        final Path file = directory.resolve("file.txt");
        Files.writeString(file, "file");

        final ServerMemoryBudget budget = ServerMemoryBudget.builder()
                .heapPercentage(50)
                .directMemoryPercentage(50)
                .build();

        final RestApiBuilder restApiBuilder =
                new RestApiBuilder("memory-rest", "memory budget churn", 1, "test");
        restApiBuilder.get("/hello", StaticRestHandler.txt("hello"));

        final FileSet files = FileSet.builder().file("/file", file).build();

        try (NettyServer rest = RestServer.of(restApiBuilder.build())
                .withMaxContentLength(1024)
                .withMemoryBudget(budget, 1024)
                .start(bootstrap().minConnections(4).maxConnections(64));
                NettyServer fileServer = FileServer.of(files)
                        .withMaxContentLength(1024)
                        .withChunkSize(1024)
                        .withMemoryBudget(budget)
                        .start(bootstrap().maxConnections(64));
                NettyServer websocket = WsServer.of(new WsApiBuilder(1).build())
                        .withMaxContentLength(1024)
                        .withMaxFramePayloadLength(1024)
                        .withMemoryBudget(budget, 1024)
                        .start(bootstrap().maxConnections(64))) {

            // what a floor holds while nothing is connected, which is what everything has to come back to
            final long floorHeapBytes = budget.snapshot().reservedHeapBytes();
            final long floorDirectMemoryBytes = budget.snapshot().reservedDirectMemoryBytes();
            Assertions.assertTrue(floorHeapBytes > 0, "The REST floor should be reserved already");

            final int[] ports = {rest.port(), fileServer.port(), websocket.port()};
            final int threads = 6;
            final int rounds = 40;
            final CountDownLatch go = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            final AtomicInteger failures = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int first = t;
                final Thread worker = new Thread(() -> {
                    try {
                        go.await();
                        for (int round = 0; round < rounds; round++) {
                            final int port = ports[(first + round) % ports.length];
                            try (Socket connection = new Socket(HOST, port)) {
                                connection.getOutputStream().flush();
                            } catch (final IOException refused) {
                                // a refusal is an outcome this test allows: what it checks is that
                                // neither outcome leaves bytes reserved behind
                            }
                        }
                    } catch (final Exception failed) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            go.countDown();
            Assertions.assertTrue(done.await(60, TimeUnit.SECONDS), "The churn did not finish");
            Assertions.assertEquals(0, failures.get());

            await(() -> budget.snapshot().connections() == 0);
            await(() -> budget.snapshot().reservedHeapBytes() == floorHeapBytes);
            await(() -> budget.snapshot().reservedDirectMemoryBytes() == floorDirectMemoryBytes);
        }

        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    private static NettyServerBuilder bootstrap() {
        return new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .bossThreads(1)
                .workerThreads(1)
                .maxConnections(10)
                .writeBufferWaterMark(1024, 2048);
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                Assertions.fail("Condition was not met before the timeout");
            }
            Thread.sleep(10);
        }
    }
}
