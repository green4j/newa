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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A process-wide admission budget shared by any number of servers. Each accepted connection reserves the
 * estimate of its server, and gives it back when the channel closes. A server may reserve a guaranteed
 * connection floor when it registers. A connection is refused when either the heap or the direct-memory
 * capacity would be crossed.
 * <p>
 * The capacities are percentages of the maxima visible to this JVM: {@link Runtime#maxMemory()} for heap and
 * Netty's estimate of the effective direct-memory limit for direct buffers. The unbudgeted percentages are
 * where application state, allocator slack, metaspace and estimation error belong.
 * <p>
 * This does not promise that an estimate is right and does not sample current memory use. It makes the
 * arithmetic a deployment would otherwise turn into one fixed limit reusable: a slot released by a REST
 * server may immediately be consumed by a file or WebSocket server with a different per-connection cost.
 */
public final class ServerMemoryBudget {
    private static final InternalLogger LOGGER =
            InternalLoggerFactory.getInstance(ServerMemoryBudget.class);

    private static final int FULL_PERCENTAGE = 100;
    private static final int DEFAULT_PERCENTAGE = 70;

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
         * @param percentage of the maximum JVM heap available to admitted connections, in [1, 100]
         * @return this builder
         */
        public Builder heapPercentage(final int percentage) {
            checkPercentage("heapPercentage", percentage);
            this.heapPercentage = percentage;
            return this;
        }

        /**
         * @param percentage of the effective direct-memory maximum available to admitted connections,
         *                   in [1, 100]
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
     * Receives point-in-time views of every change to the budget. Calls happen after the accounting lock has
     * been released and may arrive concurrently from the event loops of different servers. Gauge values in
     * an event do not change; its registration reference is the live identity of the server concerned.
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
     * An immutable process-wide view of the accounting. Reserved bytes include active connections and
     * capacity held for guaranteed connection floors which has not been used yet.
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
     * The reservation held by one connection.
     */
    public static final class Lease implements AutoCloseable {
        private final Registration registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(final Registration registration) {
            this.registration = registration;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registration.budget.release(registration);
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
                    lease = new Lease(registration);
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
        }
        notifyConnectionReleased(event);
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
