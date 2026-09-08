/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

class MemoryBudgetNettyServerTest {
    private static final String HOST = "127.0.0.1";

    @Test
    @SuppressWarnings("deprecation")
    void memoryEstimatorsSeeTheEffectiveWriteWatermarkOverride() {
        final NettyServerBuilder builder = new NettyServerBuilder()
                .writeBufferWaterMark(10, 20)
                .childOption(
                        ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(30, 40)
                )
                .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 35)
                .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 45);

        Assertions.assertEquals(35, builder.writeBufferWaterMarkLow());
        Assertions.assertEquals(45, builder.writeBufferWaterMarkHigh());
    }

    @Test
    void builderCombinesTheLocalLimitWithTheSharedBudgetAndClosesItsRegistration() throws Exception {
        final RecordingObserver observer = new RecordingObserver();
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(100, 100)
                .observer(observer)
                .build();
        final AtomicInteger initializations = new AtomicInteger();
        final NettyServer server = new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .minConnections(1)
                .maxConnections(1)
                .memoryBudget("test", budget, ServerMemoryEstimate.of(1, 1))
                // only an admitted channel is ever built: a refused one is closed before the child
                // handler is added
                .pipeline(pipeline -> initializations.incrementAndGet())
                .start();

        try {
            Assertions.assertNotNull(server.memoryRegistrationSnapshot());
            Assertions.assertEquals(1, observer.registered.get());

            try (Socket admitted = new Socket(HOST, server.port());
                    Socket refused = new Socket(HOST, server.port())) {
                await(() -> server.memoryRegistrationSnapshot().connections() == 1);
                await(() -> observer.refused.get() == 1);
                Assertions.assertEquals(1, initializations.get());
                Assertions.assertEquals(
                        ServerMemoryBudget.RefusalReason.CONNECTION_LIMIT,
                        observer.refusal.get().refusalReason()
                );
                Assertions.assertSame(
                        observer.registration.get(),
                        observer.refusal.get().registration()
                );
                Assertions.assertTrue(admitted.isConnected());
                Assertions.assertTrue(refused.isConnected());
            }

            await(() -> budget.snapshot().connections() == 0);
        } finally {
            server.close();
        }

        Assertions.assertTrue(server.memoryRegistrationSnapshot().closed());
        Assertions.assertEquals(1, observer.closed.get());
    }

    @Test
    void aFailedBindReturnsTheRegistration() throws Exception {
        final RecordingObserver observer = new RecordingObserver();
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(100, 100)
                .observer(observer)
                .build();

        try (NettyServer occupying = new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .pipeline(pipeline -> {
                })
                .start()) {
            final NettyServerBuilder conflicting = new NettyServerBuilder()
                    .host(HOST)
                    .port(occupying.port())
                    .workerThreads(1)
                    .minConnections(2)
                    .memoryBudget("conflicting", budget, ServerMemoryEstimate.of(1, 1))
                    .pipeline(pipeline -> {
                    });

            Assertions.assertThrows(IllegalStateException.class, conflicting::start);
            Assertions.assertEquals(observer.registered.get(), observer.closed.get());
            Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
            Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
        }
    }

    @Test
    void aConnectionFloorRequiresAMemoryBudget() {
        final NettyServerBuilder builder = new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .minConnections(1)
                .pipeline(pipeline -> {
                });

        Assertions.assertThrows(IllegalStateException.class, builder::start);
    }

    @Test
    void aRefusedConnectionNeverRunsTheUserInitializer() throws Exception {
        final RecordingObserver observer = new RecordingObserver();
        // the whole of both maxima, so that one connection is all this budget has: the blocker below
        // takes it, and the server's own admission is the refusal this test is about
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(1, 1)
                .heapPercentage(100)
                .directMemoryPercentage(100)
                .observer(observer)
                .build();
        final ServerMemoryBudget.Registration blocker = budget.register(
                "blocker",
                ServerMemoryEstimate.of(1, 1),
                0
        );
        final ServerMemoryBudget.Lease occupied = blocker.tryAcquire();
        final AtomicInteger initializations = new AtomicInteger();

        try (NettyServer server = new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .memoryBudget("test", budget, ServerMemoryEstimate.of(1, 1))
                .pipeline(pipeline -> initializations.incrementAndGet())
                .start();
                Socket refused = new Socket(HOST, server.port())) {
            await(() -> observer.refused.get() == 1);
            Assertions.assertEquals(0, initializations.get());
        } finally {
            occupied.close();
            blocker.close();
        }
    }

    private static final class RecordingObserver implements ServerMemoryBudget.Observer {
        private final AtomicInteger registered = new AtomicInteger();
        private final AtomicInteger refused = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicReference<ServerMemoryBudget.Registration> registration =
                new AtomicReference<>();
        private final AtomicReference<ServerMemoryBudget.Event> refusal =
                new AtomicReference<>();

        @Override
        public void onServerRegistered(final ServerMemoryBudget.Event event) {
            registered.incrementAndGet();
            registration.set(event.registration());
        }

        @Override
        public void onConnectionRefused(final ServerMemoryBudget.Event event) {
            refused.incrementAndGet();
            refusal.set(event);
        }

        @Override
        public void onServerClosed(final ServerMemoryBudget.Event event) {
            closed.incrementAndGet();
        }
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
