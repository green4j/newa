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

        result.error(new InternalServerErrorException(
                "Too many chunked responses in flight: " + chunks.maxOpenCursors(),
                HttpResponseStatus.SERVICE_UNAVAILABLE
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
