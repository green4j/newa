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

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Admission control for chunked responses, shared by the handlers which serve them. A slot is taken before
 * the cursor is opened, so a request which cannot have one never touches whatever is behind it.
 */
final class ChunkedResponses {
    private ChunkedResponses() {
    }

    /**
     * Takes a cursor slot, or answers {@code 503} if there is none.
     *
     * @param context of the request
     * @param result to refuse through
     * @return false if the request was refused and is already answered
     */
    static boolean admit(final RestContext context,
                         final RestHandle.Result result) {
        final ResponseChunks chunks = context.responseChunks();
        if (chunks.tryOpenCursor()) {
            return true;
        }

        final RestApiObserver observer = context.observer();
        if (observer != null) {
            observer.onCursorRefused(chunks.openCursors());
        }

        // a deliberate answer rather than a failure: the message says what the limit was, and is meant to be
        // read by whoever asked
        result.error(new HttpException(
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                "Too many chunked responses in flight: " + chunks.maxOpenCursors()
        ));
        return false;
    }

    /**
     * Gives a slot back when no cursor was opened after all, so nothing else will give it back.
     *
     * @param context of the request
     */
    static void giveBackSlot(final RestContext context) {
        context.responseChunks().cursorClosed();
    }

    /**
     * Reports that the cursor behind an admitted request is open.
     *
     * @param context of the request
     */
    static void cursorOpened(final RestContext context) {
        final RestApiObserver observer = context.observer();
        if (observer != null) {
            observer.onCursorOpened(context.responseChunks().openCursors());
        }
    }
}
