package io.github.green4j.newa.lang;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Returns monotonically increasing current time in milliseconds.
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

    public static long backwardJumpCount() {
        return INSTANCE.backwardJumpCount.get();
    }

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
