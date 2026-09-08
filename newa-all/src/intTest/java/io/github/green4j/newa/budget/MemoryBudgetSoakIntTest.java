/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import io.github.green4j.newa.budget.harness.BudgetServerHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a day of traffic does to the accounting, in a minute. Connections arrive and leave across all three
 * servers while the container is held to a memory limit it could be pushed past, and what this watches is
 * drift: a lease which was never given back, a floor which never came home, memory which only ever grows.
 *
 * <p>{@code -Psoak=10m} makes it a real one. A minute is what the default buys, which is enough to catch a
 * reservation leaking per connection and not much else.
 */
@Tag("soak")
class MemoryBudgetSoakIntTest {
    private static final int MEMORY_LIMIT_MB = 320;
    private static final int HEAP_MB = 96;
    private static final int DIRECT_MEMORY_MB = 64;
    private static final int MAX_CONTENT_LENGTH = 64 * 1024;
    private static final int MAX_FRAME_PAYLOAD_LENGTH = 64 * 1024;

    private static final int WORKERS = 12;

    private static BudgetHarnessContainer container;

    @BeforeAll
    static void beforeAll() {
        container = new BudgetHarnessContainer()
                .withMemoryLimitMb(MEMORY_LIMIT_MB)
                .withHeapMb(HEAP_MB)
                .withDirectMemoryMb(DIRECT_MEMORY_MB)
                .withEnv("NEWA_MAX_CONTENT_LENGTH", Integer.toString(MAX_CONTENT_LENGTH))
                .withEnv("NEWA_MAX_FRAME", Integer.toString(MAX_FRAME_PAYLOAD_LENGTH));
        container.start();
    }

    @AfterAll
    static void afterAll() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void aSustainedChurnLeavesNoReservationBehind() throws Exception {
        final BudgetReport before = container.report();
        final long floorHeapBytes = before.value("reservedHeapBytes");
        final long floorDirectMemoryBytes = before.value("reservedDirectMemoryBytes");

        final AtomicLong served = new AtomicLong();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final long deadline = System.nanoTime() + soakDuration().toNanos();

        final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        for (int worker = 0; worker < WORKERS; worker++) {
            final int first = worker;
            workers.execute(() -> churn(first, deadline, served, failure));
        }
        workers.shutdown();

        long peakUsedDirectMemoryBytes = 0;
        while (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
            final BudgetReport during = container.report();
            Assertions.assertTrue(container.alive(), "The harness died: " + container.getLogs());
            Assertions.assertTrue(
                    during.value("reservedDirectMemoryBytes")
                            <= during.value("directMemoryCapacityBytes"),
                    "Nothing should be reserved beyond the capacity:\n" + during
            );
            peakUsedDirectMemoryBytes =
                    Math.max(peakUsedDirectMemoryBytes, during.value("usedDirectMemoryBytes"));
        }

        Assertions.assertNull(failure.get(), "A client failed for a reason which was not a refusal");
        Assertions.assertTrue(served.get() > 100, "Only " + served.get() + " exchanges were served");

        final BudgetReport after = container.awaitReport(
                report -> report.value("connections") == 0,
                Duration.ofSeconds(30)
        );
        System.out.println("Soak: " + served.get() + " exchanges, " + after.value("admitted")
                + " connections admitted and " + after.value("released") + " released, peak direct memory "
                + peakUsedDirectMemoryBytes + " of " + after.value("runtimeMaxDirectMemoryBytes")
                + ", peak container memory " + container.peakMemoryBytes()
                + " of " + container.memoryLimitBytes());

        Assertions.assertEquals(after.value("admitted"), after.value("released"),
                "Every admitted connection should have been released:\n" + after);
        Assertions.assertEquals(floorHeapBytes, after.value("reservedHeapBytes"),
                "The heap reservation should be back to the floor it started from:\n" + after);
        Assertions.assertEquals(floorDirectMemoryBytes, after.value("reservedDirectMemoryBytes"),
                "The direct-memory reservation should be back to its floor:\n" + after);
        Assertions.assertEquals(0, after.refused(),
                "Nothing about this load should have been refused:\n" + after);

        final long peakMemoryBytes = container.peakMemoryBytes();
        if (peakMemoryBytes > 0) {
            Assertions.assertTrue(
                    peakMemoryBytes < container.memoryLimitBytes(),
                    "The container reached " + peakMemoryBytes + " of its "
                            + container.memoryLimitBytes() + " byte limit"
            );
        }

        final String logs = container.getLogs();
        Assertions.assertFalse(logs.contains(BudgetServerHarness.FATAL_LINE),
                "The harness ran out of memory:\n" + logs);
        Assertions.assertFalse(logs.contains("OutOfMemory"),
                "The harness logged an out-of-memory error:\n" + logs);
        Assertions.assertFalse(logs.contains("LEAK:"),
                "Netty's leak detector reported a buffer nobody released:\n" + logs);
    }

    // One worker's share of the churn: a REST exchange, a file download and a WebSocket session in turn,
    // each on a connection of its own which is opened and closed again.
    private static void churn(final int first,
                              final long deadline,
                              final AtomicLong served,
                              final AtomicReference<Throwable> failure) {
        final byte[] frame = new byte[MAX_FRAME_PAYLOAD_LENGTH];
        final byte[] body = new byte[MAX_CONTENT_LENGTH];
        for (int round = first; System.nanoTime() < deadline; round++) {
            try {
                switch (round % 3) {
                    case 0:
                        // a maximum-sized body in and a response out, which is the whole of what the REST
                        // estimate is made of, taken and given back once a round
                        assertAnswered(HttpProbe.post(container.getHost(), container.restPort(),
                                BudgetServerHarness.SINK_PATH, body));
                        break;
                    case 1:
                        assertAnswered(HttpProbe.get(container.getHost(), container.filePort(),
                                BudgetServerHarness.FILE_PATH));
                        break;
                    default:
                        echo(frame);
                        break;
                }
                served.incrementAndGet();
            } catch (final Throwable failed) {
                failure.compareAndSet(null, failed);
                return;
            }
        }
    }

    private static void echo(final byte[] frame) throws Exception {
        try (RawWsClient session = new RawWsClient(container.getHost(), container.webSocketPort())) {
            Assertions.assertTrue(session.handshake(BudgetServerHarness.WEBSOCKET_PATH),
                    "The handshake was not accepted");
            session.sendText(frame);
            Assertions.assertEquals(frame.length, session.readFrameLength(),
                    "The session should have been sent its own frame back");
        }
    }

    private static void assertAnswered(final int status) {
        Assertions.assertEquals(200, status, "The server did not answer");
    }

    /**
     * @return how long to churn for: {@code -Psoak=90s}, {@code -Psoak=10m}, or a minute by default
     */
    private static Duration soakDuration() {
        final String property = System.getProperty("newa.soak.duration", "60s").trim();
        if (property.startsWith("P")) {
            return Duration.parse(property);
        }
        final char unit = property.charAt(property.length() - 1);
        if (Character.isDigit(unit)) {
            return Duration.ofSeconds(Long.parseLong(property));
        }
        final long amount = Long.parseLong(property.substring(0, property.length() - 1).trim());
        switch (unit) {
            case 's':
                return Duration.ofSeconds(amount);
            case 'm':
                return Duration.ofMinutes(amount);
            case 'h':
                return Duration.ofHours(amount);
            default:
                throw new IllegalArgumentException("soak must end in s, m or h, got " + property);
        }
    }
}
