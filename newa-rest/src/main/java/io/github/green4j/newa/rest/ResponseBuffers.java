/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * Policy for the thread-local buffers the JSON and plain-text handlers render responses into.
 * <p>
 * Those buffers are reused between requests, which is what keeps responses allocation-free, but they grow to
 * the size of the largest response ever rendered on the thread. One rare multi-megabyte response would
 * therefore leave every event loop thread that rendered one holding a multi-megabyte array for the lifetime
 * of the process: memory that is never returned even though the load that needed it is long over, and, in a
 * container, a step towards being killed for RSS rather than failing with {@link OutOfMemoryError}.
 * <p>
 * A buffer is never dropped and never falls below {@link #baseSize()} - it starts there, so the size ordinary
 * responses need is always already allocated. Above that it follows the load: over the last
 * {@link #observationWindowMillis()} the largest response rendered on the thread is remembered, and the
 * buffer is shrunk to that size plus half of it again, but only when that is smaller than what it currently
 * holds. Nothing here counts requests: under load a thread serves hundreds a second, and any per-request
 * counter would have the buffer released and re-grown constantly, turning a memory problem into a GC one.
 * The window is what distinguishes a burst which is still going from one which is over.
 * <p>
 * Note that neither setting bounds the peak: a response is still rendered in full before it is written, so
 * concurrent large responses cost their full size each, whatever these buffers do afterwards.
 */
public final class ResponseBuffers {
    /**
     * System property overriding {@link #DEFAULT_BASE_SIZE}, in bytes.
     */
    public static final String BASE_SIZE_PROPERTY = "newa.rest.baseBufferSize";

    /**
     * System property overriding {@link #DEFAULT_OBSERVATION_WINDOW_MILLIS}.
     */
    public static final String OBSERVATION_WINDOW_MILLIS_PROPERTY = "newa.rest.bufferObservationWindowMillis";

    /**
     * Large enough that ordinary responses never grow their buffer at all, small enough that the per-thread
     * residue is negligible: a few dozen threads hold a few megabytes between them.
     */
    public static final int DEFAULT_BASE_SIZE = 64 * 1024;

    /**
     * Long enough to cover a burst of large responses, or large ones interleaved with small ones, without
     * mistaking a gap between them for the end of the load; short enough that memory comes back promptly once
     * it really is over.
     */
    public static final int DEFAULT_OBSERVATION_WINDOW_MILLIS = 5_000;

    /**
     * The window is sampled this many times, so a buffer is looked at - and at most once resized - per
     * {@link #observationWindowMillis()} / this. Everything else a response does here is one comparison.
     */
    static final int OBSERVATION_BUCKETS = 10;

    private static final int MIN_BASE_SIZE = 1024;
    private static final int MIN_OBSERVATION_WINDOW_MILLIS = 100;

    private static final int BASE_SIZE = Math.max(
            MIN_BASE_SIZE,
            Integer.getInteger(BASE_SIZE_PROPERTY, DEFAULT_BASE_SIZE)
    );

    private static final int OBSERVATION_WINDOW_MILLIS = Math.max(
            MIN_OBSERVATION_WINDOW_MILLIS,
            Integer.getInteger(OBSERVATION_WINDOW_MILLIS_PROPERTY, DEFAULT_OBSERVATION_WINDOW_MILLIS)
    );

    private ResponseBuffers() {
    }

    /**
     * @return size a thread-local response buffer starts at and is never shrunk below, in bytes
     */
    public static int baseSize() {
        return BASE_SIZE;
    }

    /**
     * @return how far back, in milliseconds, the largest response is remembered when deciding how much buffer
     *         the current load needs
     */
    public static int observationWindowMillis() {
        return OBSERVATION_WINDOW_MILLIS;
    }
}
