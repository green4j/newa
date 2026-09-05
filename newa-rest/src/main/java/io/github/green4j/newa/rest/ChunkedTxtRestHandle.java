/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;

/**
 * A plain-text response walked over by a cursor instead of being built in full. See
 * {@link ChunkedJsonRestHandle} for how the pulling works.
 */
public interface ChunkedTxtRestHandle {
    /**
     * Opens a cursor for one request. Nothing has been sent yet, so an exception thrown from this still
     * becomes an ordinary error response.
     *
     * @param context of the request
     * @return cursor over the response, closed by the framework however the response ends
     * @throws HttpException to answer with its status - {@link PathNotFoundException} when the request
     *                         addresses nothing, {@link BadRequestException} when it is malformed, or one of
     *                         your own carrying a status of its own
     */
    Cursor open(RestContext context) throws HttpException;

    interface Cursor {
        /**
         * Writes the next part of the text - one line, a batch of them, whatever one step of the underlying
         * collection is. The step size is what bounds the memory this response costs.
         *
         * @param output to write into
         * @return false when the text is complete
         */
        boolean writeNext(LineAppendable output);

        /**
         * Releases whatever the cursor holds. Called exactly once, whether the response was completed, failed
         * or abandoned because the peer went away.
         */
        void close();
    }
}
