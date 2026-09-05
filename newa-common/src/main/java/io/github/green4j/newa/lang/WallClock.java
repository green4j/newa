/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The current time in milliseconds, read from a field instead of from the system clock: a daemon thread
 * samples {@link System#currentTimeMillis()} once a millisecond, so a caller pays one volatile read and the
 * answer may be that much behind.
 * <p>
 * It never moves backwards. A system clock stepped back - NTP correcting a drift - leaves this one where it
 * was until the system catches up, and the step is counted instead, so a duration measured across one is
 * never negative.
 */
public final class WallClock extends Thread {
    private static final int UPDATE_TIME_PERIOD_NANOS = 1_000_000;

    private static final WallClock INSTANCE = new WallClock();

    static {
        INSTANCE.start();
    }

    public static long currentTimeMillis() {
        return INSTANCE.currentTimeMillis;
    }

    /**
     * @return how many times the system clock has been seen to step back since this JVM started
     */
    public static long backwardJumpCount() {
        return INSTANCE.backwardJumpCount.get();
    }

    /**
     * @return the largest single backward step of the system clock seen so far, in milliseconds
     */
    public static long maxBackwardJumpMs() {
        return INSTANCE.maxBackwardJumpMillis;
    }

    private final AtomicLong backwardJumpCount = new AtomicLong();

    private volatile long currentTimeMillis;
    private volatile long maxBackwardJumpMillis;

    private long lastSystemTimeMillis;

    private WallClock() {
        super("WallClock");
        setDaemon(true);
        currentTimeMillis = System.currentTimeMillis();
    }

    @Override
    public void run() {
        long nextUpdateNanos = System.nanoTime() + UPDATE_TIME_PERIOD_NANOS;

        while (!Thread.interrupted()) {
            updateTime();

            final long now = System.nanoTime();
            final long parkNanos = nextUpdateNanos - now;

            if (parkNanos > 0) {
                LockSupport.parkNanos(parkNanos);
            }

            nextUpdateNanos += UPDATE_TIME_PERIOD_NANOS;
            if (nextUpdateNanos < now) { // not really expected
                nextUpdateNanos = now + UPDATE_TIME_PERIOD_NANOS;
            }
        }
    }

    private void updateTime() {
        final long systemTime = System.currentTimeMillis();
        final long current = currentTimeMillis;

        if (systemTime > current) {
            // normal case: time moved forward
            currentTimeMillis = systemTime;
            lastSystemTimeMillis = systemTime;

        } else if (systemTime < lastSystemTimeMillis) {
            // NTP moved time backwards - track but don't update
            final long jump = lastSystemTimeMillis - systemTime;
            backwardJumpCount.incrementAndGet();
            if (jump > maxBackwardJumpMillis) {
                maxBackwardJumpMillis = jump;
            }
            lastSystemTimeMillis = systemTime;
            // optionally: slowly advance to allow catch-up
            // currentTimeMillis = current + 1;
        }
    }
}
