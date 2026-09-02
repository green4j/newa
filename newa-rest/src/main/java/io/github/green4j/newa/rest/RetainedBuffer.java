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

import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * One thread's response rendering buffer, kept for the lifetime of the thread, plus the decision of how much
 * of it the current load actually needs. See {@link ResponseBuffers} for why that decision is taken on a time
 * window rather than per response.
 * <p>
 * The buffer object itself is never replaced - only the array behind it is, and only ever by
 * {@link #resize} down to a size which is still at least {@link ResponseBuffers#baseSize()}. Callers can
 * therefore hold on to what {@link #buffer()} returns.
 *
 * @param <T> buffer type
 */
final class RetainedBuffer<T> {
    private final T buffer;
    private final ToIntFunction<T> capacity;
    private final ToIntFunction<T> length;
    private final IntConsumer resize;

    private final int baseSize;
    private final long bucketNanos;
    private final LongSupplier clock;

    /** Largest response seen in each slice of the window, oldest to newest around {@link #bucket}. */
    private final int[] observed = new int[ResponseBuffers.OBSERVATION_BUCKETS];

    private int bucket;
    private long bucketEndsAt;

    RetainedBuffer(final T buffer,
                   final ToIntFunction<T> capacity,
                   final ToIntFunction<T> length,
                   final IntConsumer resize) {
        this(
                buffer,
                capacity,
                length,
                resize,
                ResponseBuffers.baseSize(),
                TimeUnit.MILLISECONDS.toNanos(ResponseBuffers.observationWindowMillis()),
                System::nanoTime
        );
    }

    RetainedBuffer(final T buffer,
                   final ToIntFunction<T> capacity,
                   final ToIntFunction<T> length,
                   final IntConsumer resize,
                   final int baseSize,
                   final long windowNanos,
                   final LongSupplier clock) {
        this.buffer = buffer;
        this.capacity = capacity;
        this.length = length;
        this.resize = resize;
        this.baseSize = baseSize;
        this.bucketNanos = Math.max(1, windowNanos / observed.length);
        this.clock = clock;

        bucketEndsAt = clock.getAsLong() + bucketNanos;
    }

    T buffer() {
        return buffer;
    }

    /**
     * Reports that something has been rendered and copied out - a whole response, or one chunk of a chunked
     * one. Call once per such unit.
     */
    void onRendered() {
        final int rendered = length.applyAsInt(buffer);
        final long now = clock.getAsLong();

        if (now < bucketEndsAt) { // the usual case: one comparison, and remember the size if it is a new high
            record(rendered);
            return;
        }

        roll(now);
        record(rendered);
        shrinkToWhatTheWindowNeeds();
    }

    private void record(final int rendered) {
        if (rendered > observed[bucket]) {
            observed[bucket] = rendered;
        }
    }

    private void roll(final long now) {
        // a thread which went quiet may be several slices behind, but never needs to forget more than the
        // whole window
        final long behind = now - bucketEndsAt;
        final int toForget = (int) Math.min(observed.length, behind / bucketNanos + 1);
        for (int i = 0; i < toForget; i++) {
            bucket = (bucket + 1) % observed.length;
            observed[bucket] = 0;
        }
        bucketEndsAt = now + bucketNanos;
    }

    private void shrinkToWhatTheWindowNeeds() {
        int largest = 0;
        for (final int seen : observed) {
            if (seen > largest) {
                largest = seen;
            }
        }

        final int needed = withHeadroom(largest);
        if (needed < capacity.applyAsInt(buffer)) {
            resize.accept(needed);
        }
    }

    /**
     * The largest response of the window plus half of it again: enough that the responses this load is
     * actually made of keep fitting, and that a somewhat larger one costs a single growth rather than a
     * doubling chain.
     *
     * @param largest response rendered within the window, in bytes
     * @return size the buffer should be, never below {@link ResponseBuffers#baseSize()}
     */
    private int withHeadroom(final int largest) {
        if (largest > Integer.MAX_VALUE / 3 * 2) {
            return Integer.MAX_VALUE; // nothing to shrink to
        }
        return Math.max(baseSize, largest + (largest >> 1));
    }
}
