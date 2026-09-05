/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Policy for responses sent in chunks, pulled from a cursor rather than rendered in full - and the accounting
 * that goes with it. Built once, when the server is assembled, and handed to every
 * {@link RestApiHandler}:
 * <pre>{@code
 * ResponseChunks chunks = ResponseChunks.builder()
 *         .size(128 * 1024)
 *         .maxOpenCursors(256)
 *         .build();
 * }</pre>
 * <p>
 * Everything a chunked response costs is bounded by {@link #size()} and by how much one step of the cursor
 * writes: the framework asks the cursor for more only until the buffer has crossed the chunk size, hands the
 * bytes to the channel, and asks again - and stops asking altogether while the channel is over its write
 * watermark. So the cursor's step size is the real knob: a step which writes a hundred rows costs a hundred
 * rows of memory, whatever the collection behind it holds.
 * <p>
 * That leaves the peer which stops reading. Nothing is blocked by it - the cursor is simply not stepped - but
 * a cursor which is never stepped again is a database snapshot, a file handle or a lock held for as long as
 * the connection lingers, which for a peer that vanished without a FIN can be hours. Two things bound that,
 * and only one of them is here:
 * <ul>
 *   <li>{@link #maxOpenCursors()} - a request which would open one cursor too many is answered
 *       {@code 503 Service Unavailable} before its cursor is opened at all, so the resource is never taken.
 *       Unlimited by default: what a cursor holds is yours to know, and refusing requests is not something to
 *       start doing behind your back.</li>
 *   <li>{@link io.github.green4j.newa.server.ResponseDeadlineHandler}, in the pipeline rather than in this
 *       policy, closes a connection whose peer has stopped taking what was written to it - a chunk per
 *       window, which is what a chunked response has always been given here. It is not a property of chunked
 *       responses and never was: a file and an ordinary response are owed the same judgement, and one handler
 *       is where all three get it. {@code RestServer.withResponseDeadlineMs} sets it.</li>
 * </ul>
 */
public final class ResponseChunks {
    /**
     * Small enough to stay under the write watermarks a channel is usually configured with, large enough that
     * the per-chunk write, flush and chunk header are negligible against the payload.
     */
    public static final int DEFAULT_SIZE = 64 * 1024;

    /**
     * No limit, which is what the framework itself needs: a suspended cursor costs it one buffer. Set it to
     * what the resource behind your cursors can actually take.
     */
    public static final int UNLIMITED_OPEN_CURSORS = 0;

    private static final int MIN_SIZE = 256;

    private static final ResponseChunks DEFAULTS = builder().build();

    /**
     * @return the policy every {@link RestApiHandler} built without one uses
     */
    public static ResponseChunks defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int size = DEFAULT_SIZE;
        private int maxOpenCursors = UNLIMITED_OPEN_CURSORS;

        private Builder() {
        }

        /**
         * @param bytes a chunk is filled to before it is handed to the channel, at least 256
         * @return this
         */
        public Builder size(final int bytes) {
            this.size = Math.max(MIN_SIZE, bytes);
            return this;
        }

        /**
         * @param cursors that may be open at once across the whole server, or
         *                {@link #UNLIMITED_OPEN_CURSORS}
         * @return this
         */
        public Builder maxOpenCursors(final int cursors) {
            this.maxOpenCursors = Math.max(UNLIMITED_OPEN_CURSORS, cursors);
            return this;
        }

        public ResponseChunks build() {
            return new ResponseChunks(size, maxOpenCursors);
        }
    }

    private final int size;
    private final int maxOpenCursors;

    private final AtomicInteger openCursors = new AtomicInteger();

    private ResponseChunks(final int size,
                           final int maxOpenCursors) {
        this.size = size;
        this.maxOpenCursors = maxOpenCursors;
    }

    /**
     * @return how many bytes a chunk is filled to before it is handed to the channel; a chunk overshoots this
     *         by whatever the cursor step which crossed it wrote
     */
    public int size() {
        return size;
    }

    /**
     * @return how many cursors may be open at once, or {@link #UNLIMITED_OPEN_CURSORS}
     */
    public int maxOpenCursors() {
        return maxOpenCursors;
    }

    /**
     * @return how many cursors are open right now, across every channel of this server
     */
    public int openCursors() {
        return openCursors.get();
    }

    /**
     * Takes one of the available cursor slots. Cursors are opened and closed on event loop threads, of which
     * there are several, so this is the one thing here that has to be atomic.
     *
     * @return false if the server is already serving as many chunked responses as it is allowed to
     */
    boolean tryOpenCursor() {
        if (maxOpenCursors == UNLIMITED_OPEN_CURSORS) {
            openCursors.incrementAndGet();
            return true;
        }
        while (true) {
            final int open = openCursors.get();
            if (open >= maxOpenCursors) {
                return false;
            }
            if (openCursors.compareAndSet(open, open + 1)) {
                return true;
            }
        }
    }

    /**
     * Gives a slot back. Balanced with {@link #tryOpenCursor()} exactly once per cursor.
     *
     * @return how many cursors are left open
     */
    int cursorClosed() {
        return openCursors.decrementAndGet();
    }
}
