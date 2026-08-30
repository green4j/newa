package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;

/**
 * A JSON response walked over by a cursor instead of being built in full. The framework steps the cursor only
 * as fast as the peer takes what it produced, so the response can be arbitrarily large while the memory it
 * costs stays at one chunk.
 * <p>
 * The cursor owns the document's structure. A collection is typically
 * {@code output.startArray()} on the first step and nothing to close at the end - the framework ends the
 * document for you, closing any array or object still open.
 */
public interface ChunkedJsonRestHandle {
    /**
     * Opens a cursor for one request. Everything which can fail on the request itself belongs here: nothing
     * has been sent yet, so an exception thrown from this still becomes an ordinary error response.
     *
     * @param context of the request
     * @return cursor over the response, closed by the framework however the response ends
     * @throws PathNotFoundException if the request addresses nothing
     * @throws BadRequestException if the request is malformed
     */
    Cursor open(RestContext context) throws PathNotFoundException, BadRequestException;

    interface Cursor {
        /**
         * Writes the next part of the document - one row, a batch of them, whatever one step of the
         * underlying collection is. The step size is what bounds the memory this response costs.
         *
         * @param output to write into
         * @return false when the document is complete
         */
        boolean writeNext(JsonGenerator output);

        /**
         * Releases whatever the cursor holds. Called exactly once, whether the document was completed, failed
         * or abandoned because the peer went away.
         */
        void close();
    }
}
