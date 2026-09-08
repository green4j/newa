/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import io.github.green4j.newa.budget.harness.BudgetServerHarness;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * The control {@link OomResistanceIntTest} needs to mean anything: the same servers, the same JVM limits and
 * the same flood, with no budget in front of them. This one expects the process to die.
 *
 * <p>Excluded from {@code intTest} unless {@code -PincludeControl} asks for it. It kills a container on
 * purpose, and how much load it takes to do that is a property of the machine rather than of the code, so it
 * is a demonstration to run when the numbers above are in doubt rather than a check to run every time.
 */
@Tag("control")
class UnbudgetedOomIntTest {
    private static final int MEMORY_LIMIT_MB = 320;
    private static final int HEAP_MB = 96;
    private static final int DIRECT_MEMORY_MB = 64;
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    /**
     * Half again as many as the budgeted run offers, because here nothing refuses any of them: 96 bodies of
     * a megabyte against a 64 MiB direct-memory ceiling.
     */
    private static final int FLOOD_CONNECTIONS = 96;

    @Test
    void withoutABudgetTheSameFloodEndsTheProcess() {
        try (BudgetHarnessContainer container = new BudgetHarnessContainer()
                .withoutBudget()
                .withMemoryLimitMb(MEMORY_LIMIT_MB)
                .withHeapMb(HEAP_MB)
                .withDirectMemoryMb(DIRECT_MEMORY_MB)
                .withEnv("NEWA_MAX_CONTENT_LENGTH", Integer.toString(MAX_CONTENT_LENGTH))) {
            container.start();
            Assertions.assertFalse(container.report().budgeted(), "This harness should have no budget");

            try (SlowReaderFlood flood = new SlowReaderFlood(
                    container.getHost(), container.restPort(), MAX_CONTENT_LENGTH)) {
                flood.connect(FLOOD_CONNECTIONS);

                final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (container.alive()) {
                    if (System.nanoTime() >= deadline) {
                        Assertions.fail("The unbudgeted harness survived " + FLOOD_CONNECTIONS
                                + " connections holding " + MAX_CONTENT_LENGTH + " bytes each, which is "
                                + "more than its ceilings allow. Its log says:\n" + container.getLogs());
                    }
                    BudgetHarnessContainer.sleep(200);
                }
            }

            final String logs = container.getLogs();
            Assertions.assertTrue(
                    Long.valueOf(BudgetServerHarness.OOM_EXIT_CODE).equals(container.exitCode())
                            || container.oomKilled(),
                    "The process should have ended over memory, and it exited with "
                            + container.exitCode() + " (killed by the kernel: " + container.oomKilled()
                            + "):\n" + logs
            );
            Assertions.assertTrue(
                    logs.contains(BudgetServerHarness.FATAL_LINE) || logs.contains("OutOfMemory")
                            || container.oomKilled(),
                    "Nothing in the log says it was memory:\n" + logs
            );
        }
    }
}
