/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
    void aReservationCompetesWithConnectionsForBothCapacities() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration rest = budget.register(
                "rest",
                ServerMemoryEstimate.of(60, 10),
                0
        );

        final ServerMemoryBudget.Lease work = budget.tryReserve(50, 0);
        Assertions.assertNotNull(work);
        Assertions.assertNull(rest.tryAcquire(), "A connection was admitted into reserved heap");

        work.close();
        work.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());

        final ServerMemoryBudget.Lease connection = rest.tryAcquire();
        Assertions.assertNotNull(connection);
        Assertions.assertNull(budget.tryReserve(41, 0), "The heap was oversubscribed");
        Assertions.assertNull(budget.tryReserve(0, 91), "The direct memory was oversubscribed");

        final ServerMemoryBudget.Lease remainder = budget.tryReserve(40, 90);
        Assertions.assertNotNull(remainder);
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(100, budget.snapshot().reservedDirectMemoryBytes());

        remainder.close();
        connection.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void aReservationCannotSpendAConnectionFloor() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Registration rest = budget.register(
                "rest",
                ServerMemoryEstimate.of(40, 40),
                2,
                0
        );

        Assertions.assertNull(budget.tryReserve(21, 0));

        final ServerMemoryBudget.Lease work = budget.tryReserve(20, 20);
        Assertions.assertNotNull(work);
        Assertions.assertNotNull(rest.tryAcquire());
        Assertions.assertNotNull(rest.tryAcquire());
    }

    @Test
    void aWaitingReservationWithRoomReturnsAtOnce() throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();

        final ServerMemoryBudget.Lease blocking = budget.reserve(30, 40);
        final ServerMemoryBudget.Lease timed = budget.tryReserve(30, 40, 0, TimeUnit.SECONDS);

        Assertions.assertNotNull(blocking);
        Assertions.assertNotNull(timed);
        Assertions.assertEquals(60, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(80, budget.snapshot().reservedDirectMemoryBytes());
        blocking.close();
        timed.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @ParameterizedTest(name = "{0} returned, timed={1}")
    @CsvSource({
            "lease,      false",
            "connection, false",
            "floor,      false",
            "lease,      true"
    })
    void aWaitingReservationWakesWhenTheCapacityIsReturned(final String returned,
                                                           final boolean timed) throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final AutoCloseable occupant;
        switch (returned) {
            case "lease":
                occupant = budget.tryReserve(100, 0);
                break;
            case "connection":
                occupant = budget.register("rest", ServerMemoryEstimate.of(100, 1), 0).tryAcquire();
                break;
            case "floor":
                occupant = budget.register("rest", ServerMemoryEstimate.of(50, 1), 2, 0);
                break;
            default:
                throw new IllegalArgumentException(returned);
        }
        Assertions.assertNotNull(occupant);

        final Waiter waiter = new Waiter("budget-waiter", timed
                ? () -> budget.tryReserve(100, 0, 1, TimeUnit.MINUTES)
                : () -> budget.reserve(100, 0));
        waiter.awaitWaiting();
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());

        occupant.close();
        final ServerMemoryBudget.Lease lease = waiter.join();

        Assertions.assertNotNull(lease);
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        lease.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
    }

    @ParameterizedTest(name = "full heap={0}, full direct={1}: reserve({2}, {3})")
    @CsvSource({
            "100, 0,   0,  50",
            "0,   100, 50, 0"
    })
    void aZeroSizeNeverWaitsForItsCapacity(final long fullHeap,
                                           final long fullDirect,
                                           final long heap,
                                           final long direct) throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease full = budget.tryReserve(fullHeap, fullDirect);
        Assertions.assertNotNull(full);

        final ServerMemoryBudget.Lease blocking =
                new Waiter("budget-zero", () -> budget.reserve(heap, direct)).join();
        final ServerMemoryBudget.Lease timed = budget.tryReserve(heap, direct, 0, TimeUnit.SECONDS);

        Assertions.assertNotNull(blocking);
        Assertions.assertNotNull(timed);
        Assertions.assertEquals(fullHeap + 2 * heap, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(fullDirect + 2 * direct, budget.snapshot().reservedDirectMemoryBytes());
        blocking.close();
        timed.close();
        full.close();
    }

    @Test
    void aWaiterTakesBothSizesTogetherAndHoldsNothingWhileWaiting() throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease direct = budget.tryReserve(0, 100);
        final ServerMemoryBudget.Lease heap = budget.tryReserve(60, 0);

        final Waiter waiter = new Waiter("budget-both", () -> budget.reserve(40, 50));
        waiter.awaitWaiting();
        Assertions.assertEquals(60, budget.snapshot().reservedHeapBytes(), "The waiter holds heap");

        final ServerMemoryBudget.Lease meanwhile = budget.tryReserve(40, 0);
        Assertions.assertNotNull(meanwhile, "The heap the waiter needs was taken from others");
        meanwhile.close();

        heap.close();
        waiter.awaitWaiting();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes(), "The waiter took the heap alone");

        direct.close();
        final ServerMemoryBudget.Lease lease = waiter.join();

        Assertions.assertNotNull(lease);
        Assertions.assertEquals(40, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(50, budget.snapshot().reservedDirectMemoryBytes());
        lease.close();
    }

    @Test
    void twoWaitersNeverSplitTheCapacityWhichFitsOne() throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease occupant = budget.tryReserve(100, 100);
        final Waiter first = new Waiter("budget-first", () -> budget.reserve(60, 60));
        final Waiter second = new Waiter("budget-second", () -> budget.reserve(60, 60));
        first.awaitWaiting();
        second.awaitWaiting();

        occupant.close();
        awaitTrue(() -> first.lease.get() != null || second.lease.get() != null,
                "Neither waiter took the returned capacity");
        final Waiter served = first.lease.get() != null ? first : second;
        final Waiter waiting = served == first ? second : first;
        waiting.awaitWaiting();

        Assertions.assertNull(waiting.lease.get());
        Assertions.assertEquals(60, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(60, budget.snapshot().reservedDirectMemoryBytes());

        served.join().close();
        waiting.join().close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void aTimedReservationGivesUpAfterItsTimeout() throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease occupant = budget.tryReserve(100, 0);

        final long start = System.nanoTime();
        Assertions.assertNull(budget.tryReserve(1, 0, 20, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(20));
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
        occupant.close();
    }

    @ParameterizedTest(name = "reserve({0}, {1})")
    @CsvSource({
            "101, 0",
            "0,   101"
    })
    void whatNoWaitCanFitIsRefusedWithoutWaiting(final long heap,
                                                 final long direct) throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();

        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserve(heap, direct));
        Assertions.assertNull(
                new Waiter("budget-oversized", () -> budget.tryReserve(heap, direct, 1, TimeUnit.HOURS)).join());
        Assertions.assertNull(budget.tryReserve(heap, direct));
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @ParameterizedTest(name = "timed={0}")
    @CsvSource({"false", "true"})
    void anInterruptedWaiterReservesNothing(final boolean timed) throws Exception {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease occupant = budget.tryReserve(100, 100);
        final Waiter waiter = new Waiter("budget-interrupted", timed
                ? () -> budget.tryReserve(50, 50, 1, TimeUnit.MINUTES)
                : () -> budget.reserve(50, 50));
        waiter.awaitWaiting();

        waiter.thread.interrupt();
        Assertions.assertNull(waiter.join());

        Assertions.assertInstanceOf(InterruptedException.class, waiter.failure.get());
        Assertions.assertEquals(100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(100, budget.snapshot().reservedDirectMemoryBytes());
        occupant.close();
        budget.reserve(100, 100).close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
    }

    @Test
    void anInterruptBeforeTheCallReservesNothingEvenWithRoom() {
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        try {
            Thread.currentThread().interrupt();
            Assertions.assertThrows(InterruptedException.class, () -> budget.reserve(1, 1));
            Thread.currentThread().interrupt();
            Assertions.assertThrows(InterruptedException.class,
                    () -> budget.tryReserve(1, 1, 1, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();
        }
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({
            "heap,   immediate",
            "heap,   blocking",
            "heap,   timed",
            "direct, immediate",
            "direct, blocking",
            "direct, timed"
    })
    void aSingleKindReservationTakesAndWaitsForItsOwnCapacityOnly(final String kind,
                                                                  final String form) throws Exception {
        final boolean heap = kind.equals("heap");
        final ServerMemoryBudget budget = budgetOf(100, 100).build();
        final ServerMemoryBudget.Lease otherKindFull = heap ? budget.tryReserve(0, 100) : budget.tryReserve(100, 0);

        final ServerMemoryBudget.Lease first = reserveOneKind(budget, heap, form, 40);
        Assertions.assertNotNull(first, "The full capacity of the other kind was consulted");
        Assertions.assertEquals(heap ? 40 : 100, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(heap ? 100 : 40, budget.snapshot().reservedDirectMemoryBytes());

        if (form.equals("immediate")) {
            Assertions.assertNull(reserveOneKind(budget, heap, form, 61));
        } else {
            final Waiter waiter = new Waiter("budget-" + kind, () -> reserveOneKind(budget, heap, form, 61));
            waiter.awaitWaiting();
            first.close();
            final ServerMemoryBudget.Lease second = waiter.join();
            Assertions.assertNotNull(second);
            Assertions.assertEquals(heap ? 61 : 100, budget.snapshot().reservedHeapBytes());
            Assertions.assertEquals(heap ? 100 : 61, budget.snapshot().reservedDirectMemoryBytes());
            second.close();
        }
        first.close();
        otherKindFull.close();
        Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
    }

    @Test
    void invalidConfigurationIsRefused() throws Exception {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryBudget.builder(1, 1).heapPercentage(0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryBudget.builder(1, 1).directMemoryPercentage(101));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryEstimate.of(-1, 1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ServerMemoryEstimate.of(0, 0));
        final ServerMemoryBudget budget = ServerMemoryBudget.builder(Long.MAX_VALUE, 10).build();
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(0, -1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserve(0, -1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> budget.tryReserve(-1, 0, 1, TimeUnit.SECONDS));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> budget.tryReserve(0, -1, 1, TimeUnit.SECONDS));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.tryReserveHeap(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserveHeap(-1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> budget.tryReserveHeap(-1, 1, TimeUnit.SECONDS));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.tryReserveDirectMemory(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserveDirectMemory(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> budget.reserveDirectMemory(8));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> budget.tryReserveDirectMemory(-1, 1, TimeUnit.SECONDS));
        Assertions.assertNull(budget.tryReserveDirectMemory(8, 1, TimeUnit.SECONDS));
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

    private static ServerMemoryBudget.Lease reserveOneKind(final ServerMemoryBudget budget,
                                                           final boolean heap,
                                                           final String form,
                                                           final long bytes) throws InterruptedException {
        switch (form) {
            case "immediate":
                return heap ? budget.tryReserveHeap(bytes) : budget.tryReserveDirectMemory(bytes);
            case "blocking":
                return heap ? budget.reserveHeap(bytes) : budget.reserveDirectMemory(bytes);
            case "timed":
                return heap
                        ? budget.tryReserveHeap(bytes, 1, TimeUnit.MINUTES)
                        : budget.tryReserveDirectMemory(bytes, 1, TimeUnit.MINUTES);
            default:
                throw new IllegalArgumentException(form);
        }
    }

    private static void awaitTrue(final BooleanSupplier condition,
                                  final String failure) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                Assertions.fail(failure);
            }
            Thread.yield();
        }
    }

    private interface Reservation {
        ServerMemoryBudget.Lease reserve() throws InterruptedException;
    }

    /**
     * A thread which makes one reservation and keeps what it ended with: the lease, or the failure.
     */
    private static final class Waiter {
        private final AtomicReference<ServerMemoryBudget.Lease> lease = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private Waiter(final String name,
                       final Reservation reservation) {
            thread = new Thread(() -> {
                try {
                    lease.set(reservation.reserve());
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }, name);
            thread.start();
        }

        /**
         * Returns once the thread is parked in the budget's monitor, so that a test acts on a real waiter
         * rather than on one which has not reached the wait yet.
         */
        private void awaitWaiting() {
            awaitTrue(() -> {
                final Thread.State state = thread.getState();
                return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            }, thread.getName() + " never waited, it is " + thread.getState());
        }

        private ServerMemoryBudget.Lease join() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            Assertions.assertFalse(thread.isAlive(), thread.getName() + " is still waiting");
            final Throwable t = failure.get();
            if (t != null && !(t instanceof InterruptedException)) {
                throw new AssertionError(thread.getName() + " failed", t);
            }
            return lease.get();
        }
    }
}
