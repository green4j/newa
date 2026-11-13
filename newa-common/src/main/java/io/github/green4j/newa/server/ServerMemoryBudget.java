/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An admission budget shared by any number of servers in one process. Each accepted connection reserves the
 * estimate of its server and gives it back when the channel closes. A server may reserve a guaranteed
 * connection floor when it registers, and application work may reserve its own bytes with
 * {@link #tryReserve(long, long)}, or wait for them with {@link #reserve(long, long)} and
 * {@link #tryReserve(long, long, long, TimeUnit)}. In this instance, the sum of active estimates, unused
 * guaranteed floors and those reservations never crosses either capacity.
 * <p>
 * {@link #builder()} captures the maxima visible at that call: {@link Runtime#maxMemory()} for heap and
 * {@link PlatformDependent#maxDirectMemory()} for Netty direct memory. The former follows an explicit
 * {@code -Xmx}, or the JVM's container ergonomics such as {@code -XX:MaxRAMPercentage}; the latter follows a
 * positive, raw-byte {@code -Dio.netty.maxDirectMemory}, or Netty's estimate of the JVM
 * {@code -XX:MaxDirectMemorySize}. This class does not read cgroups. Their memory limit remains the hard
 * bound on heap, all direct and native pools, metaspace, code cache and thread stacks together.
 * <p>
 * This is not a memory sampler or an allocator limit. It does not subtract memory already used, recheck a
 * connection which grows after admission, or account for another budget instance, an unregistered server,
 * global application state, other libraries, allocator pooling and fragmentation, or native memory outside
 * Netty's effective direct limit. A response or outbound-frame estimate is not by itself a hard output
 * limit. An estimate also relies on the concurrency bounds of the pipeline it was derived from. Application
 * state, retained buffers and custom pipeline staging have to be included in the server's additional
 * estimate; process-global costs and estimation error need headroom outside the percentages.
 * <p>
 * Consequently this class does not promise that actual heap, direct memory or process RSS stays under its
 * external limit. Its snapshots and observer events report admission accounting, not allocation. The value
 * of the arithmetic is that a slot released by a REST server may immediately be consumed by a file or
 * WebSocket server with a different per-connection cost while all registrations on this instance remain
 * within their configured estimates.
 */
public final class ServerMemoryBudget {
    private static final InternalLogger LOGGER =
            InternalLoggerFactory.getInstance(ServerMemoryBudget.class);

    private static final int FULL_PERCENTAGE = 100;
    private static final int DEFAULT_PERCENTAGE = 70;

    /**
     * Captures the JVM heap and Netty direct-memory maxima which the returned builder will divide. Later
     * changes to deployment limits are not reflected in this builder or a budget built from it.
     *
     * @return a builder over the maxima visible at this call
     */
    public static Builder builder() {
        return new Builder(
                Runtime.getRuntime().maxMemory(),
                PlatformDependent.maxDirectMemory()
        );
    }

    /**
     * Deterministic maxima for the tests in this package.
     *
     * @param maximumHeapBytes heap maximum
     * @param maximumDirectMemoryBytes direct-memory maximum
     * @return a builder over those maxima
     */
    static Builder builder(final long maximumHeapBytes,
                           final long maximumDirectMemoryBytes) {
        return new Builder(maximumHeapBytes, maximumDirectMemoryBytes);
    }

    public static final class Builder {
        private final long maximumHeapBytes;
        private final long maximumDirectMemoryBytes;

        private int heapPercentage = DEFAULT_PERCENTAGE;
        private int directMemoryPercentage = DEFAULT_PERCENTAGE;
        private Observer observer;

        private Builder(final long maximumHeapBytes,
                        final long maximumDirectMemoryBytes) {
            this.maximumHeapBytes = maximumHeapBytes;
            this.maximumDirectMemoryBytes = maximumDirectMemoryBytes;
        }

        /**
         * @param percentage of the maximum JVM heap this budget divides, in [1, 100]
         * @return this builder
         */
        public Builder heapPercentage(final int percentage) {
            checkPercentage("heapPercentage", percentage);
            this.heapPercentage = percentage;
            return this;
        }

        /**
         * @param percentage of the effective direct-memory maximum this budget divides, in [1, 100]
         * @return this builder
         */
        public Builder directMemoryPercentage(final int percentage) {
            checkPercentage("directMemoryPercentage", percentage);
            this.directMemoryPercentage = percentage;
            return this;
        }

        /**
         * Sets where admission, refusal, release and server lifecycle events are reported. Observer failures
         * do not affect admission.
         *
         * @param observer to notify, null to observe nothing
         * @return this builder
         */
        public Builder observer(final Observer observer) {
            this.observer = observer;
            return this;
        }

        public ServerMemoryBudget build() {
            if (maximumHeapBytes < 1) {
                throw new IllegalStateException(
                        "The JVM reported no usable maximum heap: " + maximumHeapBytes);
            }
            if (maximumDirectMemoryBytes < 1) {
                throw new IllegalStateException(
                        "Netty reported no usable direct-memory maximum: "
                                + maximumDirectMemoryBytes);
            }
            return new ServerMemoryBudget(
                    maximumHeapBytes,
                    maximumDirectMemoryBytes,
                    heapPercentage,
                    directMemoryPercentage,
                    observer
            );
        }

        private static void checkPercentage(final String name,
                                            final int percentage) {
            if (percentage < 1 || percentage > FULL_PERCENTAGE) {
                throw new IllegalArgumentException(
                        name + " must be in [1, 100], got " + percentage);
            }
        }
    }

    public enum RefusalReason {
        REGISTRATION_CLOSED,
        CONNECTION_LIMIT,
        HEAP,
        DIRECT_MEMORY,
        HEAP_AND_DIRECT_MEMORY
    }

    /**
     * Receives point-in-time views of every change a server makes to the budget. Calls happen after the
     * accounting lock has been released and may arrive concurrently from the event loops of different
     * servers. Gauge values in an event do not change; its registration reference is the live identity of the
     * server concerned. An application reservation, immediate or waiting, is not reported: its caller has
     * the answer, and its bytes show in the gauges.
     */
    public interface Observer {
        default void onServerRegistered(final Event event) {
        }

        default void onConnectionAdmitted(final Event event) {
        }

        default void onConnectionRefused(final Event event) {
        }

        default void onConnectionReleased(final Event event) {
        }

        default void onServerClosed(final Event event) {
        }
    }

    /**
     * The process and server gauges at the point of one observed budget event.
     */
    public static final class Event {
        private final Registration registration;
        private final ServerMemoryEstimate estimate;
        private final int minConnections;
        private final int maxConnections;
        private final long serverConnections;
        private final long processConnections;
        private final long reservedHeapBytes;
        private final long reservedDirectMemoryBytes;
        private final long heapCapacityBytes;
        private final long directMemoryCapacityBytes;
        private final RefusalReason refusalReason;

        private Event(final ServerMemoryBudget budget,
                      final Registration registration,
                      final RefusalReason refusalReason) {
            this.registration = registration;
            estimate = registration.estimate;
            minConnections = registration.minConnections;
            maxConnections = registration.maxConnections;
            serverConnections = registration.connections;
            processConnections = budget.connections;
            reservedHeapBytes = budget.reservedHeapBytes;
            reservedDirectMemoryBytes = budget.reservedDirectMemoryBytes;
            heapCapacityBytes = budget.heapCapacityBytes;
            directMemoryCapacityBytes = budget.directMemoryCapacityBytes;
            this.refusalReason = refusalReason;
        }

        /**
         * @return the stable registration identity of the server which caused this event
         */
        public Registration registration() {
            return registration;
        }

        public ServerMemoryEstimate estimate() {
            return estimate;
        }

        public int minConnections() {
            return minConnections;
        }

        public int maxConnections() {
            return maxConnections;
        }

        public long serverConnections() {
            return serverConnections;
        }

        public long processConnections() {
            return processConnections;
        }

        public long reservedHeapBytes() {
            return reservedHeapBytes;
        }

        public long reservedDirectMemoryBytes() {
            return reservedDirectMemoryBytes;
        }

        public long heapCapacityBytes() {
            return heapCapacityBytes;
        }

        public long directMemoryCapacityBytes() {
            return directMemoryCapacityBytes;
        }

        /**
         * @return why admission was refused, null for every other event
         */
        public RefusalReason refusalReason() {
            return refusalReason;
        }
    }

    /**
     * An immutable process-wide view of the accounting. Reserved bytes include active connections, capacity
     * held for guaranteed connection floors which has not been used yet, and open application reservations.
     */
    public static final class Snapshot {
        private final long maximumHeapBytes;
        private final long maximumDirectMemoryBytes;
        private final long heapCapacityBytes;
        private final long directMemoryCapacityBytes;
        private final long reservedHeapBytes;
        private final long reservedDirectMemoryBytes;
        private final long connections;

        private Snapshot(final ServerMemoryBudget budget) {
            maximumHeapBytes = budget.maximumHeapBytes;
            maximumDirectMemoryBytes = budget.maximumDirectMemoryBytes;
            heapCapacityBytes = budget.heapCapacityBytes;
            directMemoryCapacityBytes = budget.directMemoryCapacityBytes;
            reservedHeapBytes = budget.reservedHeapBytes;
            reservedDirectMemoryBytes = budget.reservedDirectMemoryBytes;
            connections = budget.connections;
        }

        public long maximumHeapBytes() {
            return maximumHeapBytes;
        }

        public long maximumDirectMemoryBytes() {
            return maximumDirectMemoryBytes;
        }

        public long heapCapacityBytes() {
            return heapCapacityBytes;
        }

        public long directMemoryCapacityBytes() {
            return directMemoryCapacityBytes;
        }

        public long reservedHeapBytes() {
            return reservedHeapBytes;
        }

        public long reservedDirectMemoryBytes() {
            return reservedDirectMemoryBytes;
        }

        public long connections() {
            return connections;
        }
    }

    /**
     * One server's share of this budget. A registration may reserve a connection floor and have its own
     * connection ceiling in addition to the two shared byte capacities.
     */
    public static final class Registration implements AutoCloseable {
        private final ServerMemoryBudget budget;
        private final String name;
        private final ServerMemoryEstimate estimate;
        private final int minConnections;
        private final int maxConnections;

        private long connections;
        private boolean closed;

        private Registration(final ServerMemoryBudget budget,
                             final String name,
                             final ServerMemoryEstimate estimate,
                             final int minConnections,
                             final int maxConnections) {
            this.budget = budget;
            this.name = name;
            this.estimate = estimate;
            this.minConnections = minConnections;
            this.maxConnections = maxConnections;
        }

        /**
         * Tries to reserve this server's per-connection estimate.
         *
         * @return a lease to close with the connection, or null when admission was refused
         */
        public Lease tryAcquire() {
            return budget.acquire(this);
        }

        public RegistrationSnapshot snapshot() {
            return budget.snapshot(this);
        }

        @Override
        public void close() {
            budget.close(this);
        }
    }

    /**
     * The reservation held by one connection or taken by one application reservation. It may be closed from
     * any thread, and only the first close returns it.
     */
    public static final class Lease implements AutoCloseable {
        /**
         * Reserves nothing: what code which reserves memory is given where there is no budget to reserve from.
         * A budget never returns it; a budget without room returns null.
         */
        public static final Lease NO_BUDGET = new Lease(() -> {
        });

        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(final Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    /**
     * An immutable view of one server registration.
     */
    public static final class RegistrationSnapshot {
        private final String name;
        private final ServerMemoryEstimate estimate;
        private final int minConnections;
        private final int maxConnections;
        private final long connections;
        private final boolean closed;

        private RegistrationSnapshot(final Registration registration) {
            name = registration.name;
            estimate = registration.estimate;
            minConnections = registration.minConnections;
            maxConnections = registration.maxConnections;
            connections = registration.connections;
            closed = registration.closed;
        }

        public String name() {
            return name;
        }

        public ServerMemoryEstimate estimate() {
            return estimate;
        }

        public int minConnections() {
            return minConnections;
        }

        public int maxConnections() {
            return maxConnections;
        }

        public long connections() {
            return connections;
        }

        public boolean closed() {
            return closed;
        }
    }

    private final long maximumHeapBytes;
    private final long maximumDirectMemoryBytes;
    private final long heapCapacityBytes;
    private final long directMemoryCapacityBytes;
    private final Observer observer;

    private long reservedHeapBytes;
    private long reservedDirectMemoryBytes;
    private long connections;

    private ServerMemoryBudget(final long maximumHeapBytes,
                               final long maximumDirectMemoryBytes,
                               final int heapPercentage,
                               final int directMemoryPercentage,
                               final Observer observer) {
        this.maximumHeapBytes = maximumHeapBytes;
        this.maximumDirectMemoryBytes = maximumDirectMemoryBytes;
        this.heapCapacityBytes = percentageOf(maximumHeapBytes, heapPercentage);
        this.directMemoryCapacityBytes =
                percentageOf(maximumDirectMemoryBytes, directMemoryPercentage);
        this.observer = observer;
    }

    /**
     * Registers a server. Zero {@code maxConnections} means that only the shared byte capacities limit it.
     *
     * @param name identifying the server in its snapshot
     * @param estimate reserved by each admitted connection
     * @param maxConnections local ceiling, or 0 for none
     * @return the registration to put at the head of every accepted channel
     */
    public Registration register(final String name,
                                 final ServerMemoryEstimate estimate,
                                 final int maxConnections) {
        return register(name, estimate, 0, maxConnections);
    }

    /**
     * Registers a server and reserves enough capacity to admit its connection floor. Zero
     * {@code minConnections} means no capacity is held aside; zero {@code maxConnections} means that only
     * the shared byte capacities limit admissions above the floor.
     *
     * @param name identifying the server in its snapshot
     * @param estimate reserved by each admitted connection
     * @param minConnections guaranteed local floor, or 0 for none
     * @param maxConnections local ceiling, or 0 for none
     * @return the registration to put at the head of every accepted channel
     */
    public Registration register(final String name,
                                 final ServerMemoryEstimate estimate,
                                 final int minConnections,
                                 final int maxConnections) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A server memory registration requires a name");
        }
        if (estimate == null) {
            throw new IllegalArgumentException("A server memory estimate is required");
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException(
                    "maxConnections must not be negative: " + maxConnections);
        }
        if (minConnections < 0) {
            throw new IllegalArgumentException(
                    "minConnections must not be negative: " + minConnections);
        }
        if (maxConnections > 0 && minConnections > maxConnections) {
            throw new IllegalArgumentException(
                    "minConnections must not exceed maxConnections: "
                            + minConnections + " > " + maxConnections);
        }

        final Registration registration =
                new Registration(this, name, estimate, minConnections, maxConnections);
        final long guaranteedHeapBytes = guaranteedBytes(
                "heap",
                estimate.heapBytesPerConnection(),
                minConnections
        );
        final long guaranteedDirectMemoryBytes = guaranteedBytes(
                "directMemory",
                estimate.directMemoryBytesPerConnection(),
                minConnections
        );
        final Event event;
        synchronized (this) {
            final boolean heapFull =
                    guaranteedHeapBytes > heapCapacityBytes - reservedHeapBytes;
            final boolean directFull = guaranteedDirectMemoryBytes
                    > directMemoryCapacityBytes - reservedDirectMemoryBytes;
            if (heapFull || directFull) {
                throw new IllegalStateException(
                        "The guaranteed connection floor for " + name
                                + " does not fit the remaining "
                                + capacityName(heapFull, directFull) + " capacity");
            }
            reservedHeapBytes += guaranteedHeapBytes;
            reservedDirectMemoryBytes += guaranteedDirectMemoryBytes;
            event = event(registration, null);
        }
        notifyServerRegistered(event);
        return registration;
    }

    /**
     * Tries to reserve memory for one piece of application work, for the time a request holds its rows, say.
     * It draws on the capacity left by connections and floors, and until it is closed connections cannot
     * have those bytes. The sizes are the caller's forecast: nothing measures or limits what the work then
     * allocates, and a lease which is never closed keeps its bytes for the life of this budget.
     *
     * @param heapBytes estimated heap the work holds
     * @param directMemoryBytes estimated direct memory the work holds
     * @return a lease to close when the work has ended, or null when either capacity has no room for it
     */
    public Lease tryReserve(final long heapBytes,
                            final long directMemoryBytes) {
        checkSizes(heapBytes, directMemoryBytes);
        synchronized (this) {
            if (!fits(heapBytes, directMemoryBytes)) {
                return null;
            }
            add(heapBytes, directMemoryBytes);
        }
        return lease(heapBytes, directMemoryBytes);
    }

    /**
     * Reserves memory for one piece of application work as {@link #tryReserve(long, long)} does, waiting until
     * both capacities have room for it at the same moment. Nothing is held while waiting, and a zero size
     * never waits for its capacity. There is no fairness: connections and immediate reservations may take
     * freed bytes ahead of a waiter, so under sustained load a large reservation may wait indefinitely, as
     * does one which fits the capacity but not what floors of open registrations and leases never closed
     * leave of it. Connections are released on Netty event loops, so it must not be called on one: the loop
     * would stall the releases it waits for, and every channel it carries.
     *
     * @param heapBytes estimated heap the work holds
     * @param directMemoryBytes estimated direct memory the work holds
     * @return a lease to close when the work has ended
     * @throws IllegalArgumentException when a size is negative or exceeds its capacity, which no wait can fit
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease reserve(final long heapBytes,
                         final long directMemoryBytes) throws InterruptedException {
        checkSizes(heapBytes, directMemoryBytes);
        if (exceedsCapacity(heapBytes, directMemoryBytes)) {
            throw new IllegalArgumentException(
                    "The reservation exceeds the budget capacity: " + heapBytes + " heap of "
                            + heapCapacityBytes + ", " + directMemoryBytes + " direct memory of "
                            + directMemoryCapacityBytes);
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (!fits(heapBytes, directMemoryBytes)) {
                wait();
            }
            add(heapBytes, directMemoryBytes);
        }
        return lease(heapBytes, directMemoryBytes);
    }

    /**
     * Reserves memory for one piece of application work as {@link #reserve(long, long)} does, waiting at most
     * the timeout. The timeout bounds the wait; it does not guarantee progress. A size which exceeds its
     * capacity is answered at once, and a timeout of zero or less answers as {@link #tryReserve(long, long)}.
     * It must not be called on a Netty event loop, for the reason {@link #reserve(long, long)} gives.
     *
     * @param heapBytes estimated heap the work holds
     * @param directMemoryBytes estimated direct memory the work holds
     * @param timeout the longest wait
     * @param unit of the timeout
     * @return a lease to close when the work has ended, or null when the timeout elapsed first or a size
     *         exceeds its capacity
     * @throws IllegalArgumentException when a size is negative
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease tryReserve(final long heapBytes,
                            final long directMemoryBytes,
                            final long timeout,
                            final TimeUnit unit) throws InterruptedException {
        checkSizes(heapBytes, directMemoryBytes);
        if (exceedsCapacity(heapBytes, directMemoryBytes)) {
            return null;
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            while (!fits(heapBytes, directMemoryBytes)) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            add(heapBytes, directMemoryBytes);
        }
        return lease(heapBytes, directMemoryBytes);
    }

    /**
     * {@link #tryReserve(long, long)} for work which holds heap only; the direct-memory capacity is not
     * consulted.
     *
     * @param heapBytes estimated heap the work holds
     * @return a lease to close when the work has ended, or null when the heap capacity has no room for it
     */
    public Lease tryReserveHeap(final long heapBytes) {
        return tryReserve(heapBytes, 0);
    }

    /**
     * {@link #reserve(long, long)} for work which holds heap only: it waits for the heap capacity alone.
     *
     * @param heapBytes estimated heap the work holds
     * @return a lease to close when the work has ended
     * @throws IllegalArgumentException when the size is negative or exceeds the heap capacity
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease reserveHeap(final long heapBytes) throws InterruptedException {
        return reserve(heapBytes, 0);
    }

    /**
     * {@link #tryReserve(long, long, long, TimeUnit)} for work which holds heap only: it waits for the heap
     * capacity alone.
     *
     * @param heapBytes estimated heap the work holds
     * @param timeout the longest wait
     * @param unit of the timeout
     * @return a lease to close when the work has ended, or null when the timeout elapsed first or the size
     *         exceeds the heap capacity
     * @throws IllegalArgumentException when the size is negative
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease tryReserveHeap(final long heapBytes,
                                final long timeout,
                                final TimeUnit unit) throws InterruptedException {
        return tryReserve(heapBytes, 0, timeout, unit);
    }

    /**
     * {@link #tryReserve(long, long)} for work which holds direct memory only; the heap capacity is not
     * consulted.
     *
     * @param directMemoryBytes estimated direct memory the work holds
     * @return a lease to close when the work has ended, or null when the direct-memory capacity has no room
     *         for it
     */
    public Lease tryReserveDirectMemory(final long directMemoryBytes) {
        return tryReserve(0, directMemoryBytes);
    }

    /**
     * {@link #reserve(long, long)} for work which holds direct memory only: it waits for the direct-memory
     * capacity alone.
     *
     * @param directMemoryBytes estimated direct memory the work holds
     * @return a lease to close when the work has ended
     * @throws IllegalArgumentException when the size is negative or exceeds the direct-memory capacity
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease reserveDirectMemory(final long directMemoryBytes) throws InterruptedException {
        return reserve(0, directMemoryBytes);
    }

    /**
     * {@link #tryReserve(long, long, long, TimeUnit)} for work which holds direct memory only: it waits for
     * the direct-memory capacity alone.
     *
     * @param directMemoryBytes estimated direct memory the work holds
     * @param timeout the longest wait
     * @param unit of the timeout
     * @return a lease to close when the work has ended, or null when the timeout elapsed first or the size
     *         exceeds the direct-memory capacity
     * @throws IllegalArgumentException when the size is negative
     * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
     */
    public Lease tryReserveDirectMemory(final long directMemoryBytes,
                                        final long timeout,
                                        final TimeUnit unit) throws InterruptedException {
        return tryReserve(0, directMemoryBytes, timeout, unit);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    private Lease acquire(final Registration registration) {
        final Lease lease;
        final RefusalReason refusal;
        final Event event;

        synchronized (this) {
            if (registration.closed) {
                lease = null;
                refusal = RefusalReason.REGISTRATION_CLOSED;
            } else if (registration.maxConnections > 0
                    && registration.connections >= registration.maxConnections) {
                lease = null;
                refusal = RefusalReason.CONNECTION_LIMIT;
            } else {
                final long heap = registration.estimate.heapBytesPerConnection();
                final long direct = registration.estimate.directMemoryBytesPerConnection();
                final boolean guaranteed =
                        registration.connections < registration.minConnections;
                final boolean heapFull =
                        !guaranteed && heap > heapCapacityBytes - reservedHeapBytes;
                final boolean directFull = !guaranteed
                        && direct > directMemoryCapacityBytes - reservedDirectMemoryBytes;

                if (heapFull || directFull) {
                    lease = null;
                    refusal = refusal(heapFull, directFull);
                } else {
                    if (!guaranteed) {
                        reservedHeapBytes += heap;
                        reservedDirectMemoryBytes += direct;
                    }
                    connections++;
                    registration.connections++;
                    lease = new Lease(() -> release(registration));
                    refusal = null;
                }
            }
            event = event(registration, refusal);
        }

        if (lease == null) {
            notifyConnectionRefused(event);
        } else {
            notifyConnectionAdmitted(event);
        }
        return lease;
    }

    private void release(final Registration registration) {
        final Event event;
        synchronized (this) {
            if (registration.connections < 1) {
                throw new IllegalStateException(
                        "Memory budget lease released without an admitted connection");
            }
            if (registration.closed
                    || registration.connections > registration.minConnections) {
                reservedHeapBytes -= registration.estimate.heapBytesPerConnection();
                reservedDirectMemoryBytes -=
                        registration.estimate.directMemoryBytesPerConnection();
            }
            connections--;
            registration.connections--;
            event = event(registration, null);
            // Unconditional: a release within the floor frees nothing, and a woken waiter waits again.
            notifyAll();
        }
        notifyConnectionReleased(event);
    }

    private boolean fits(final long heapBytes,
                         final long directMemoryBytes) {
        return heapBytes <= heapCapacityBytes - reservedHeapBytes
                && directMemoryBytes <= directMemoryCapacityBytes - reservedDirectMemoryBytes;
    }

    private boolean exceedsCapacity(final long heapBytes,
                                    final long directMemoryBytes) {
        return heapBytes > heapCapacityBytes || directMemoryBytes > directMemoryCapacityBytes;
    }

    private void add(final long heapBytes,
                     final long directMemoryBytes) {
        reservedHeapBytes += heapBytes;
        reservedDirectMemoryBytes += directMemoryBytes;
    }

    private Lease lease(final long heapBytes,
                        final long directMemoryBytes) {
        return new Lease(() -> unreserve(heapBytes, directMemoryBytes));
    }

    private synchronized void unreserve(final long heapBytes,
                                        final long directMemoryBytes) {
        reservedHeapBytes -= heapBytes;
        reservedDirectMemoryBytes -= directMemoryBytes;
        notifyAll();
    }

    private synchronized RegistrationSnapshot snapshot(final Registration registration) {
        return new RegistrationSnapshot(registration);
    }

    private void close(final Registration registration) {
        final Event event;
        synchronized (this) {
            if (registration.closed) {
                return;
            }
            final long unusedGuarantees =
                    Math.max(0L, registration.minConnections - registration.connections);
            reservedHeapBytes -=
                    registration.estimate.heapBytesPerConnection() * unusedGuarantees;
            reservedDirectMemoryBytes -=
                    registration.estimate.directMemoryBytesPerConnection() * unusedGuarantees;
            registration.closed = true;
            event = event(registration, null);
            notifyAll();
        }
        notifyServerClosed(event);
    }

    private Event event(final Registration registration,
                        final RefusalReason refusal) {
        return observer == null ? null : new Event(this, registration, refusal);
    }

    private void notifyServerRegistered(final Event event) {
        if (event != null) {
            notify(() -> observer.onServerRegistered(event));
        }
    }

    private void notifyConnectionAdmitted(final Event event) {
        if (event != null) {
            notify(() -> observer.onConnectionAdmitted(event));
        }
    }

    private void notifyConnectionRefused(final Event event) {
        if (event != null) {
            notify(() -> observer.onConnectionRefused(event));
        }
    }

    private void notifyConnectionReleased(final Event event) {
        if (event != null) {
            notify(() -> observer.onConnectionReleased(event));
        }
    }

    private void notifyServerClosed(final Event event) {
        if (event != null) {
            notify(() -> observer.onServerClosed(event));
        }
    }

    private static void notify(final Runnable notification) {
        try {
            notification.run();
        } catch (final Exception reported) {
            // Load-bearing for onConnectionAdmitted above all: the reservation is committed and the lease
            // built before the observer is told, so an exception leaving here would return no lease to
            // close and strand that reservation for the life of the process. Debug rather than warn -
            // this is a breadcrumb on a path taken by every admission, and the contract already says an
            // observer cannot change what the budget decided.
            LOGGER.debug("A server memory budget observer failed", reported);
        }
    }

    private static void checkSizes(final long heapBytes,
                                   final long directMemoryBytes) {
        if (heapBytes < 0) {
            throw new IllegalArgumentException("heapBytes must not be negative: " + heapBytes);
        }
        if (directMemoryBytes < 0) {
            throw new IllegalArgumentException(
                    "directMemoryBytes must not be negative: " + directMemoryBytes);
        }
    }

    private static RefusalReason refusal(final boolean heapFull,
                                         final boolean directFull) {
        if (heapFull && directFull) {
            return RefusalReason.HEAP_AND_DIRECT_MEMORY;
        }
        return heapFull ? RefusalReason.HEAP : RefusalReason.DIRECT_MEMORY;
    }

    private static String capacityName(final boolean heapFull,
                                       final boolean directFull) {
        if (heapFull && directFull) {
            return "heap and direct-memory";
        }
        return heapFull ? "heap" : "direct-memory";
    }

    private static long guaranteedBytes(final String name,
                                        final long bytesPerConnection,
                                        final int minConnections) {
        try {
            return Math.multiplyExact(bytesPerConnection, (long) minConnections);
        } catch (final ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    name + " floor reservation overflows a long: "
                            + bytesPerConnection + " * " + minConnections,
                    overflow
            );
        }
    }

    private static long percentageOf(final long maximum,
                                     final int percentage) {
        return maximum / FULL_PERCENTAGE * percentage
                + maximum % FULL_PERCENTAGE * percentage / FULL_PERCENTAGE;
    }
}
