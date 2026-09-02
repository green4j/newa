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

package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

/**
 * The sizing policy on its own, driven by a clock the test moves by hand. What matters is that the buffer is
 * never dropped and never falls below the base size, that it is not shrunk while the load still needs it, and
 * that it does come back down once the load which needed it is outside the window.
 */
class RetainedBufferTest {
    private static final int BASE_SIZE = 64 * 1024;
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long BUCKET_NANOS = WINDOW_NANOS / ResponseBuffers.OBSERVATION_BUCKETS;

    /** Stands in for a rendering buffer: it grows on demand and can be resized down. */
    private static final class Buffer {
        private int capacity = BASE_SIZE;
        private int length;

        private void render(final int size) {
            length = size;
            while (capacity < size) {
                capacity <<= 1;
            }
        }
    }

    private final Buffer buffer = new Buffer();
    private long now;

    private final RetainedBuffer<Buffer> retained = new RetainedBuffer<>(
            buffer,
            b -> b.capacity,
            b -> b.length,
            size -> buffer.capacity = size,
            BASE_SIZE,
            WINDOW_NANOS,
            () -> now
    );

    private void respond(final int size) {
        buffer.render(size);
        retained.onRendered();
    }

    private void elapse(final long nanos) {
        now += nanos;
    }

    private void respondFor(final int size, final long duration) {
        final long until = now + duration;
        while (now < until) {
            respond(size);
            elapse(BUCKET_NANOS / 4);
        }
    }

    @Test
    public void testBufferInstanceIsNeverReplaced() {
        final Buffer before = retained.buffer();

        respond(16 * BASE_SIZE);
        respondFor(64, 4 * WINDOW_NANOS);

        Assertions.assertSame(before, retained.buffer());
    }

    @Test
    public void testSmallResponsesNeverShrinkBelowTheBaseSize() {
        respondFor(64, 4 * WINDOW_NANOS);

        Assertions.assertEquals(BASE_SIZE, buffer.capacity);
    }

    @Test
    public void testBufferIsKeptWhileLargeResponsesKeepComing() {
        respond(16 * BASE_SIZE);
        final int grown = buffer.capacity;
        Assertions.assertTrue(grown > BASE_SIZE);

        respondFor(16 * BASE_SIZE, 4 * WINDOW_NANOS);

        Assertions.assertEquals(grown, buffer.capacity,
                "re-growing megabytes per request costs far more than holding the buffer");
    }

    @Test
    public void testBufferIsKeptWhileLargeResponsesAreInterleavedWithSmallOnes() {
        respond(16 * BASE_SIZE);
        final int grown = buffer.capacity;

        // one large response per window is enough to keep the size alive, whatever else the thread serves
        for (int i = 0; i < 4; i++) {
            respondFor(64, WINDOW_NANOS / 2);
            respond(16 * BASE_SIZE);
        }

        Assertions.assertEquals(grown, buffer.capacity);
    }

    @Test
    public void testBufferComesBackDownOnceLargeResponsesStop() {
        respond(16 * BASE_SIZE);
        Assertions.assertTrue(buffer.capacity > BASE_SIZE);

        respondFor(64, WINDOW_NANOS + BUCKET_NANOS);

        Assertions.assertEquals(BASE_SIZE, buffer.capacity);
    }

    @Test
    public void testBufferIsNotShrunkBeforeTheWindowHasPassed() {
        respond(16 * BASE_SIZE);
        final int grown = buffer.capacity;

        respondFor(64, WINDOW_NANOS - 2 * BUCKET_NANOS);

        Assertions.assertEquals(grown, buffer.capacity,
                "a gap between large responses is not the end of the load");
    }

    @Test
    public void testBufferSettlesOnTheLargestResponseOfTheWindowPlusHalf() {
        final int size = 3 * BASE_SIZE;

        respond(32 * BASE_SIZE);
        respondFor(size, WINDOW_NANOS + BUCKET_NANOS);

        // the responses this load is made of keep fitting, and a somewhat larger one costs one growth
        Assertions.assertEquals(size + size / 2, buffer.capacity);
    }

    @Test
    public void testThreadWhichWentQuietForLongerThanTheWindowForgetsEverything() {
        respond(16 * BASE_SIZE);
        Assertions.assertTrue(buffer.capacity > BASE_SIZE);

        elapse(100 * WINDOW_NANOS);
        respond(64);

        Assertions.assertEquals(BASE_SIZE, buffer.capacity);
    }

    @Test
    public void testBufferIsLookedAtOnceABucketRatherThanOncePerResponse() {
        respond(32 * BASE_SIZE);
        respondFor(64, WINDOW_NANOS + BUCKET_NANOS);
        Assertions.assertEquals(BASE_SIZE, buffer.capacity);

        // hundreds of responses within one bucket must not cost hundreds of resizes
        final int[] resizes = new int[1];
        final RetainedBuffer<Buffer> counted = new RetainedBuffer<>(
                buffer,
                b -> b.capacity,
                b -> b.length,
                size -> {
                    resizes[0]++;
                    buffer.capacity = size;
                },
                BASE_SIZE,
                WINDOW_NANOS,
                () -> now
        );

        buffer.render(32 * BASE_SIZE);
        counted.onRendered();
        // 1000 responses spread over exactly one window, so ten bucket boundaries are crossed
        for (int i = 0; i < 1000; i++) {
            buffer.render(64);
            counted.onRendered();
            elapse(BUCKET_NANOS / 100);
        }

        Assertions.assertTrue(resizes[0] <= ResponseBuffers.OBSERVATION_BUCKETS + 1,
                "resized " + resizes[0] + " times");
    }
}
