/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import io.github.green4j.newa.budget.harness.BudgetServerHarness;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Where the capacities come from. The budget divides what {@link Runtime#maxMemory()} and Netty report, and
 * nothing in the unit tests can say what those are worth in a container: they run in a JVM sized by the
 * machine it was launched on.
 */
class ContainerLimitsIntTest {
    private static final int FULL_PERCENTAGE = 100;

    private static final int MEMORY_LIMIT_MB = 512;
    private static final int DIRECT_MEMORY_MB = 96;
    private static final int HEAP_PERCENTAGE = 70;
    private static final int DIRECT_PERCENTAGE = 60;

    @Test
    void capacitiesFollowTheContainerLimitRatherThanTheMachine() throws Exception {
        try (BudgetHarnessContainer container = new BudgetHarnessContainer()
                .withMemoryLimitMb(MEMORY_LIMIT_MB)
                .withMaxRamPercentage(50) // no -Xmx at all: the cgroup limit is all the JVM has to go on
                .withDirectMemoryMb(DIRECT_MEMORY_MB)
                .withEnv("NEWA_HEAP_PERCENTAGE", Integer.toString(HEAP_PERCENTAGE))
                .withEnv("NEWA_DIRECT_PERCENTAGE", Integer.toString(DIRECT_PERCENTAGE))) {
            container.start();

            final BudgetReport report = container.report();
            final long containerLimit = (long) MEMORY_LIMIT_MB * 1024 * 1024;
            final long half = containerLimit / 2;
            final long maximumHeap = report.value("maximumHeapBytes");

            Assertions.assertTrue(
                    maximumHeap > half * 8 / 10 && maximumHeap <= half,
                    "Half of the container's " + containerLimit + " bytes is what the budget should have "
                            + "divided, and it took " + maximumHeap + ":\n" + report
            );
            Assertions.assertEquals(
                    report.value("runtimeMaxHeapBytes"),
                    maximumHeap,
                    "The budget's heap maximum is whatever this JVM says its own is"
            );
            Assertions.assertEquals(
                    percentageOf(maximumHeap, HEAP_PERCENTAGE),
                    report.value("heapCapacityBytes")
            );

            final long maximumDirect = report.value("maximumDirectMemoryBytes");
            Assertions.assertEquals(
                    (long) DIRECT_MEMORY_MB * 1024 * 1024,
                    maximumDirect,
                    "-XX:MaxDirectMemorySize is what Netty reports and what the budget divides"
            );
            Assertions.assertEquals(
                    percentageOf(maximumDirect, DIRECT_PERCENTAGE),
                    report.value("directMemoryCapacityBytes")
            );

            Assertions.assertEquals(
                    200,
                    container.get(BudgetServerHarness.HELLO_PATH).statusCode(),
                    "The budgeted server should answer while nothing is holding its capacity"
            );
        }
    }

    @Test
    void capacitiesFollowAnExplicitHeapCeiling() throws Exception {
        final int heapMb = 128;

        try (BudgetHarnessContainer container = new BudgetHarnessContainer()
                .withMemoryLimitMb(MEMORY_LIMIT_MB)
                .withHeapMb(heapMb)
                .withDirectMemoryMb(DIRECT_MEMORY_MB)
                .withEnv("NEWA_HEAP_PERCENTAGE", Integer.toString(HEAP_PERCENTAGE))) {
            container.start();

            final BudgetReport report = container.report();
            final long asked = (long) heapMb * 1024 * 1024;
            final long maximumHeap = report.value("maximumHeapBytes");

            Assertions.assertTrue(
                    maximumHeap > asked * 8 / 10 && maximumHeap <= asked,
                    "-Xmx" + heapMb + "m should be what the budget divides, and it took "
                            + maximumHeap + ":\n" + report
            );
            Assertions.assertEquals(
                    percentageOf(maximumHeap, HEAP_PERCENTAGE),
                    report.value("heapCapacityBytes")
            );
            Assertions.assertTrue(
                    report.value("rest.heapBytesPerConnection") > 0
                            && report.value("rest.directMemoryBytesPerConnection") > 0,
                    "The REST server should have estimated something for every connection:\n" + report
            );
        }
    }

    /**
     * The arithmetic {@code ServerMemoryBudget} does, which avoids overflowing on a large maximum by
     * dividing before multiplying and adding back what the division dropped.
     *
     * @param maximum to take a percentage of
     * @param percentage to take
     * @return the capacity the budget will have computed
     */
    private static long percentageOf(final long maximum,
                                     final int percentage) {
        return maximum / FULL_PERCENTAGE * percentage
                + maximum % FULL_PERCENTAGE * percentage / FULL_PERCENTAGE;
    }
}
