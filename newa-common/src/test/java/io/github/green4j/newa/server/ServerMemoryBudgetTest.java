/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

class ServerMemoryBudgetTest {
    @Test
    void percentagesAreAppliedToTheDetectedMaximums() {
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(1_003, 2_007)
                .heapPercentage(70)
                .directMemoryPercentage(25)
                .build();

        final ServerMemoryBudget.Snapshot snapshot = budget.snapshot();
        Assertions.assertEquals(1_003, snapshot.maximumHeapBytes());
        Assertions.assertEquals(2_007, snapshot.maximumDirectMemoryBytes());
        Assertions.assertEquals(702, snapshot.heapCapacityBytes());
        Assertions.assertEquals(501, snapshot.directMemoryCapacityBytes());
    }

    @Test
    void theDefaultPercentagesLeaveASafetyMargin() {
        // what a budget built without a percentage gets, and what budgetOf() below opts out of
        final ServerMemoryBudget.Snapshot snapshot =
                ServerMemoryBudget.builder(1_000, 1_000).build().snapshot();

        Assertions.assertEquals(700, snapshot.heapCapacityBytes());
        Assertions.assertEquals(700, snapshot.directMemoryCapacityBytes());
    }

    @Test
    void differentlyWeightedServersShareBothCapacities() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration rest = budget.register(
                "rest",
                ServerMemoryEstimate.of(60, 10),
                0
        );
        final ServerMemoryBudget.Registration websocket = budget.register(
                "websocket",
                ServerMemoryEstimate.of(10, 60),
                0
        );

        final ServerMemoryBudget.Lease restLease = rest.tryAcquire();
        final ServerMemoryBudget.Lease websocketLease = websocket.tryAcquire();

        Assertions.assertNotNull(restLease);
        Assertions.assertNotNull(websocketLease);
        Assertions.assertNull(rest.tryAcquire(), "The remaining heap admitted an oversized REST connection");
        Assertions.assertNull(websocket.tryAcquire(),
                "The remaining direct memory admitted an oversized WebSocket connection");

        Assertions.assertEquals(70, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(70, budget.snapshot().reservedDirectMemoryBytes());

        restLease.close();
        websocketLease.close();

        Assertions.assertEquals(0, budget.snapshot().connections());
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void aLocalConnectionLimitStillAppliesInsideTheSharedBudget() {
        final AtomicReference<ServerMemoryBudget.Event> refusal =
                new AtomicReference<>();
        final ServerMemoryBudget budget = budgetOf(1_000, 1_000)
                .observer(new ServerMemoryBudget.Observer() {
                    @Override
                    public void onConnectionRefused(final ServerMemoryBudget.Event event) {
                        refusal.set(event);
                    }
                })
                .build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "admin",
                ServerMemoryEstimate.of(1, 1),
                1,
                1
        );

        final ServerMemoryBudget.Lease admitted = registration.tryAcquire();
        Assertions.assertNotNull(admitted);
        Assertions.assertNull(registration.tryAcquire());

        final ServerMemoryBudget.RegistrationSnapshot snapshot = registration.snapshot();
        Assertions.assertEquals(1, snapshot.connections());
        Assertions.assertEquals(1, snapshot.minConnections());
        Assertions.assertEquals(
                ServerMemoryBudget.RefusalReason.CONNECTION_LIMIT,
                refusal.get().refusalReason()
        );
        Assertions.assertSame(registration, refusal.get().registration());

        admitted.close();
    }

    @Test
    void aConnectionFloorReservesCapacityWithoutPartitioningTheRemainder() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration protectedServer = budget.register(
                "protected",
                ServerMemoryEstimate.of(30, 20),
                2,
                0
        );
        final ServerMemoryBudget.Registration sharedServer = budget.register(
                "shared",
                ServerMemoryEstimate.of(40, 60),
                0
        );

        Assertions.assertEquals(60, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(40, budget.snapshot().reservedDirectMemoryBytes());
        final ServerMemoryBudget.Lease shared = sharedServer.tryAcquire();
        Assertions.assertNotNull(shared);
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(100, budget.snapshot().reservedDirectMemoryBytes());

        final ServerMemoryBudget.Lease guaranteedOne = protectedServer.tryAcquire();
        final ServerMemoryBudget.Lease guaranteedTwo = protectedServer.tryAcquire();
        Assertions.assertNotNull(guaranteedOne);
        Assertions.assertNotNull(guaranteedTwo);
        Assertions.assertNull(protectedServer.tryAcquire());
        Assertions.assertEquals(3, budget.snapshot().connections());
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(100, budget.snapshot().reservedDirectMemoryBytes());

        shared.close();
        guaranteedOne.close();
        guaranteedTwo.close();
        Assertions.assertEquals(60, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(40, budget.snapshot().reservedDirectMemoryBytes());

        protectedServer.close();
        sharedServer.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void closingARegistrationKeepsOnlyItsActiveGuaranteedReservations() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "protected",
                ServerMemoryEstimate.of(30, 20),
                2,
                0
        );
        final ServerMemoryBudget.Lease active = registration.tryAcquire();

        registration.close();

        Assertions.assertEquals(30, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(20, budget.snapshot().reservedDirectMemoryBytes());
        active.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void aFloorWhichDoesNotFitIsRejectedAndAClosedFloorCanBeReused() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration first = budget.register(
                "first",
                ServerMemoryEstimate.of(60, 60),
                1,
                0
        );

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> budget.register(
                        "second",
                        ServerMemoryEstimate.of(50, 50),
                        1,
                        0
                )
        );

        first.close();
        final ServerMemoryBudget.Registration second = budget.register(
                "second",
                ServerMemoryEstimate.of(50, 50),
                1,
                0
        );
        Assertions.assertEquals(50, budget.snapshot().reservedHeapBytes());
        second.close();
    }

    @Test
    void aLeaseReturnsItsReservationOnlyOnce() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "rest",
                ServerMemoryEstimate.of(7, 11),
                0
        );
        final ServerMemoryBudget.Lease lease = registration.tryAcquire();

        Assertions.assertNotNull(lease);
        lease.close();
        lease.close();

        Assertions.assertEquals(0, budget.snapshot().connections());
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void aClosedRegistrationRefusesNewConnectionsButExistingLeasesStillReturn() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "rest",
                ServerMemoryEstimate.of(10, 10),
                0
        );
        final ServerMemoryBudget.Lease lease = registration.tryAcquire();
        Assertions.assertNotNull(lease);

        registration.close();

        Assertions.assertNull(registration.tryAcquire());
        Assertions.assertTrue(registration.snapshot().closed());
        lease.close();
        Assertions.assertEquals(0, budget.snapshot().connections());
    }

    @Test
    void observerReceivesTransitionsAndCurrentGaugesWithoutChangingAccounting() {
        final List<String> transitions = new ArrayList<>();
        final AtomicReference<ServerMemoryBudget.Event> refusal = new AtomicReference<>();
        final ServerMemoryBudget budget = budgetOf(10, 10)
                .observer(new ServerMemoryBudget.Observer() {
                    @Override
                    public void onServerRegistered(final ServerMemoryBudget.Event event) {
                        transitions.add("registered");
                    }

                    @Override
                    public void onConnectionAdmitted(final ServerMemoryBudget.Event event) {
                        transitions.add("admitted");
                    }

                    @Override
                    public void onConnectionRefused(final ServerMemoryBudget.Event event) {
                        transitions.add("refused");
                        refusal.set(event);
                    }

                    @Override
                    public void onConnectionReleased(final ServerMemoryBudget.Event event) {
                        transitions.add("released");
                    }

                    @Override
                    public void onServerClosed(final ServerMemoryBudget.Event event) {
                        transitions.add("closed");
                    }
                })
                .build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "all-memory",
                ServerMemoryEstimate.of(10, 10),
                0
        );

        final ServerMemoryBudget.Lease lease = registration.tryAcquire();
        Assertions.assertNotNull(lease);
        Assertions.assertNull(registration.tryAcquire());
        lease.close();
        registration.close();

        Assertions.assertEquals(
                List.of("registered", "admitted", "refused", "released", "closed"),
                transitions
        );
        Assertions.assertEquals(
                ServerMemoryBudget.RefusalReason.HEAP_AND_DIRECT_MEMORY,
                refusal.get().refusalReason()
        );
        Assertions.assertSame(registration, refusal.get().registration());
        Assertions.assertEquals(
                "all-memory",
                refusal.get().registration().snapshot().name()
        );
        Assertions.assertEquals(1, refusal.get().processConnections());
        Assertions.assertEquals(10, refusal.get().reservedHeapBytes());
        Assertions.assertEquals(10, refusal.get().reservedDirectMemoryBytes());
    }

    @Test
    void observerFailuresDoNotAffectAdmissionOrRelease() {
        final ServerMemoryBudget budget = budgetOf(10, 10)
                .observer(new ServerMemoryBudget.Observer() {
                    @Override
                    public void onConnectionAdmitted(final ServerMemoryBudget.Event event) {
                        throw new IllegalStateException("Observer failed");
                    }

                    @Override
                    public void onConnectionReleased(final ServerMemoryBudget.Event event) {
                        throw new IllegalStateException("Observer failed");
                    }
                })
                .build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "server",
                ServerMemoryEstimate.of(1, 1),
                0
        );

        final ServerMemoryBudget.Lease lease = registration.tryAcquire();
        Assertions.assertNotNull(lease);
        lease.close();

        Assertions.assertEquals(0, budget.snapshot().connections());
    }

    @Test
    void concurrentAdmissionNeverOversubscribesEitherCapacity() throws Exception {
        final int capacity = 16;
        final int contenders = 64;
        final ServerMemoryBudget budget = budgetOf(capacity, capacity).build();
        final ServerMemoryBudget.Registration registration = budget.register(
                "websocket",
                ServerMemoryEstimate.of(1, 1),
                0
        );
        final CountDownLatch start = new CountDownLatch(1);
        final List<ServerMemoryBudget.Lease> admitted =
                Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < contenders; i++) {
            final Thread contender = new Thread(() -> {
                await(start);
                final ServerMemoryBudget.Lease lease = registration.tryAcquire();
                if (lease != null) {
                    admitted.add(lease);
                }
            }, "budget-contender-" + i);
            threads.add(contender);
            contender.start();
        }

        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        Assertions.assertEquals(capacity, admitted.size());
        Assertions.assertEquals(capacity, budget.snapshot().connections());

        admitted.forEach(ServerMemoryBudget.Lease::close);
        Assertions.assertEquals(0, budget.snapshot().connections());
    }

    @Test
    void invalidConfigurationIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryBudget.builder(1, 1).heapPercentage(0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryBudget.builder(1, 1).directMemoryPercentage(101));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryEstimate.of(-1, 1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryEstimate.of(0, 0));
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(Long.MAX_VALUE, 10).build();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> budget.register(
                        "negative-floor",
                        ServerMemoryEstimate.of(1, 1),
                        -1,
                        0
                )
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> budget.register(
                        "inverted-limits",
                        ServerMemoryEstimate.of(1, 1),
                        2,
                        1
                )
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> budget.register(
                        "overflowing-floor",
                        ServerMemoryEstimate.of(Long.MAX_VALUE, 1),
                        2,
                        0
                )
        );
    }

    /**
     * The whole of both maxima, so that the numbers a test writes are the capacities it gets. The default
     * percentages are a production safety margin and belong to
     * {@link #theDefaultPercentagesLeaveASafetyMargin()}, not to the arithmetic these tests check.
     *
     * @param heapBytes maximum heap
     * @param directMemoryBytes maximum direct memory
     * @return a builder whose capacities are those two numbers
     */
    private static ServerMemoryBudget.Builder budgetOf(final long heapBytes,
                                                       final long directMemoryBytes) {
        return ServerMemoryBudget.builder(heapBytes, directMemoryBytes)
                .heapPercentage(100)
                .directMemoryPercentage(100);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to contend for the budget", interrupted);
        }
    }
}
