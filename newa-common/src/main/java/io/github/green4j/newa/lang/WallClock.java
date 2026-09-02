/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
