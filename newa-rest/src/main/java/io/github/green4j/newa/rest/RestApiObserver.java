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

/**
 * One request which was routed to an endpoint - the stages of {@link HttpApiObserver}, plus what the handler
 * and its response did.
 * <p>
 * Nothing here fires for a request which matched no endpoint: that one is done with by
 * {@link #onRequestNotRouted} instead.
 * <p>
 * The order within one request is:
 * <pre>
 * onRequestReceived -&gt; onHandlingStarted -&gt; [ onResponseFailed ] -&gt; onRequestCompleted
 * </pre>
 * and, for a response pulled from a cursor:
 * <pre>
 * onRequestReceived -&gt; onHandlingStarted -&gt; onCursorOpened
 *                   -&gt; onChunkWritten* -&gt; onCursorClosed -&gt; onRequestCompleted
 * </pre>
 * <p>
 * Only {@link #onHandlingStarted} is given the {@link RestContext}, because it is the only stage which runs
 * while all of it is still valid. Copy what the later ones need - {@link RestContext#pathExpression()} is a
 * plain string and the label a metric wants.
 * <p>
 * Every method has a no-op default. Calls come from event loop threads, so an implementation must not block.
 */
public interface RestApiObserver extends HttpApiObserver {
    /** How a chunked response ended. */
    enum Outcome {
        /** The cursor ran out and the whole response reached the peer. */
        COMPLETED,
        /** The peer took no chunk within {@link ResponseChunks#stallTimeoutMillis()}. */
        STALLED,
        /** The connection went away, or the response failed, before the cursor ran out. */
        ABANDONED
    }

    /**
     * Routed, about to be handled.
     *
     * @param context of the request, valid for the duration of this call only
     */
    default void onHandlingStarted(RestContext context) {
    }

    /**
     * A chunked response was admitted and its cursor opened.
     *
     * @param openCursors including this one
     */
    default void onCursorOpened(int openCursors) {
    }

    /**
     * Refused on {@link ResponseChunks#maxOpenCursors()} and answered {@code 503}. No cursor was opened, so
     * nothing was taken from whatever is behind it. This is the gauge of the capacity; the response it ends
     * in is {@link #onResponseFailed} like any other.
     *
     * @param openCursors at the time
     */
    default void onCursorRefused(int openCursors) {
    }

    /**
     * One chunk handed to the channel. Fires per chunk, so keep it cheap.
     *
     * @param bytes in the chunk, zero if the document ended on a chunk boundary
     */
    default void onChunkWritten(int bytes) {
    }

    /**
     * The cursor is released. Always follows {@link #onCursorOpened}, exactly once, whatever the outcome, and
     * always before {@link #onRequestCompleted}.
     *
     * @param openCursors left
     * @param bytes written before it ended
     * @param durationNanos the cursor was open
     * @param outcome it ended with
     */
    default void onCursorClosed(int openCursors,
                                long bytes,
                                long durationNanos,
                                Outcome outcome) {
    }
}
