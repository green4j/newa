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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

/**
 * The promise the budget makes, against a JVM which can actually run out: a flood of connections all
 * holding a maximum-sized body is refused down to what the capacities allow, the process lives, and the
 * capacity comes back when the flood goes away.
 *
 * <p>{@link UnbudgetedOomIntTest} is the other half of this: the same flood against the same servers with
 * the budget turned off, which is what says the flood was lethal to begin with.
 */
class OomResistanceIntTest {
    private static final int MEMORY_LIMIT_MB = 320;
    private static final int HEAP_MB = 96;
    private static final int DIRECT_MEMORY_MB = 64;
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    /**
     * Comfortably more than the direct-memory capacity can hold: 70% of 64 MiB against a per-connection
     * estimate of two maximum-sized bodies admits around twenty.
     */
    private static final int FLOOD_CONNECTIONS = 64;

    private static BudgetHarnessContainer container;

    @BeforeAll
    static void beforeAll() {
        container = new BudgetHarnessContainer()
                .withMemoryLimitMb(MEMORY_LIMIT_MB)
                .withHeapMb(HEAP_MB)
                .withDirectMemoryMb(DIRECT_MEMORY_MB)
                .withEnv("NEWA_MAX_CONTENT_LENGTH", Integer.toString(MAX_CONTENT_LENGTH));
        container.start();
    }

    @AfterAll
    static void afterAll() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void theFloodIsRefusedRatherThanRunTheProcessOutOfMemory() {
        final BudgetReport before = container.report();
        final long floorDirectMemoryBytes = before.value("reservedDirectMemoryBytes");
        Assertions.assertEquals(0, before.value("connections"));
        Assertions.assertEquals(0, before.refused());

        try (SlowReaderFlood flood = new SlowReaderFlood(
                container.getHost(), container.restPort(), MAX_CONTENT_LENGTH)) {
            flood.connect(FLOOD_CONNECTIONS);

            final BudgetReport full = container.awaitReport(
                    report -> report.value("admitted") + report.refused() >= FLOOD_CONNECTIONS,
                    Duration.ofSeconds(30)
            );
            System.out.println("Flood of " + FLOOD_CONNECTIONS + ": admitted " + full.value("admitted")
                    + ", refused " + full.refusedForMemory() + " for memory, holding "
                    + full.value("reservedDirectMemoryBytes") + " of "
                    + full.value("directMemoryCapacityBytes") + " direct bytes, using "
                    + full.value("usedDirectMemoryBytes"));

            Assertions.assertTrue(
                    full.refusedForMemory() > FLOOD_CONNECTIONS / 2,
                    "Most of the flood should have been refused for want of memory:\n" + full
            );
            Assertions.assertTrue(
                    full.value("connections") > 1 && full.value("connections") < FLOOD_CONNECTIONS,
                    "The budget should have admitted some of the flood and not all of it:\n" + full
            );
            Assertions.assertTrue(
                    full.value("reservedDirectMemoryBytes")
                            > full.value("directMemoryCapacityBytes") * 8 / 10,
                    "A budget which refused should have been nearly full when it did:\n" + full
            );
            Assertions.assertTrue(
                    full.value("reservedDirectMemoryBytes") <= full.value("directMemoryCapacityBytes"),
                    "Nothing should be reserved beyond the capacity:\n" + full
            );
            Assertions.assertTrue(
                    full.value("usedDirectMemoryBytes") < full.value("runtimeMaxDirectMemoryBytes"),
                    "The direct memory actually held should have stayed under the JVM's ceiling:\n" + full
            );

            // a refusal, not a failure: the server closes the connection before the pipeline runs
            Assertions.assertEquals(
                    -1,
                    restStatus(),
                    "A connection offered to a full budget should be closed unanswered"
            );

            assertNothingRanOutOfMemory();
        }

        final BudgetReport released = container.awaitReport(
                report -> report.value("connections") == 0,
                Duration.ofSeconds(30)
        );
        Assertions.assertEquals(
                floorDirectMemoryBytes,
                released.value("reservedDirectMemoryBytes"),
                "Every lease should have gone back, leaving only the guaranteed floor:\n" + released
        );
        Assertions.assertEquals(
                200,
                restStatus(),
                "The server should answer again once the flood has gone"
        );

        assertNothingRanOutOfMemory();
    }

    private void assertNothingRanOutOfMemory() {
        Assertions.assertTrue(container.alive(), "The harness died: " + container.getLogs());
        Assertions.assertFalse(container.oomKilled(), "The container was killed for its memory use");

        final String logs = container.getLogs();
        Assertions.assertFalse(logs.contains(BudgetServerHarness.FATAL_LINE),
                "The harness reported an out-of-memory error:\n" + logs);
        Assertions.assertFalse(logs.contains("OutOfMemory"),
                "The harness logged an out-of-memory error:\n" + logs);
        Assertions.assertFalse(logs.contains("LEAK:"),
                "Netty's leak detector reported a buffer nobody released:\n" + logs);
    }

    private static int restStatus() {
        try {
            return HttpProbe.get(container.getHost(), container.restPort(), BudgetServerHarness.HELLO_PATH);
        } catch (final IOException refused) {
            return -1; // the reset a refused connection eventually becomes
        }
    }
}
