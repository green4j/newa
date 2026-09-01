package io.github.green4j.newa.performance.ws;

import java.util.concurrent.locks.LockSupport;

/**
 * The thread which generates the messages of one channel, at the rate the run offered. One per channel and no
 * more: publications of one channel have to be serialized, or no subscriber could tell a hole from a
 * reordering. Every server is held to the same rule, so what differs between them is the delivery.
 * <p>
 * The schedule is open loop - a publication due while the previous one is going out is neither dropped nor
 * rescheduled - so a server which cannot keep up reports a lower achieved rate than it was offered. That is
 * how a server whose send blocks on its slowest subscriber admits to being slow.
 */
public final class Publisher extends Thread {
    /**
     * How long before a publication is due the thread stops parking and spins instead: a parked thread wakes
     * with a granularity coarser than the periods at the rates worth measuring.
     */
    private static final long SPIN_NANOS = 50_000L;

    private static final long STOP_TIMEOUT_MILLIS = 5000;

    private final long periodNanos;
    private final Runnable publication;

    private volatile boolean running = true;

    /**
     * @param channel     index this thread publishes into, for the thread's name
     * @param rate        messages per second to publish at
     * @param publication to run once per message
     */
    public Publisher(final int channel,
                     final long rate,
                     final Runnable publication) {
        super("newa-perf-publisher-" + channel);
        setDaemon(true);
        this.periodNanos = Math.max(1L, 1_000_000_000L / rate);
        this.publication = publication;
    }

    @Override
    public void run() {
        long dueNanos = System.nanoTime();
        while (running) {
            final long now = System.nanoTime();
            final long remaining = dueNanos - now;
            if (remaining <= 0) {
                publication.run();
                dueNanos += periodNanos;
                continue;
            }
            if (remaining > SPIN_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Stops publishing and waits for the thread to notice.
     */
    public void stopAndJoin() {
        running = false;
        LockSupport.unpark(this);
        try {
            join(STOP_TIMEOUT_MILLIS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
