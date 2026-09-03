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

import io.netty.buffer.ByteBuf;

/**
 * A response of any content type at all, walked over by a cursor which writes raw bytes. The framework steps
 * it only as fast as the peer takes what it produced, so the response can be arbitrarily large while the
 * memory it costs stays at one chunk.
 * <p>
 * This is the general form of {@link ChunkedJsonRestHandle} and {@link ChunkedTxtRestHandle}: those two write
 * characters and go through a rendering buffer on the way out, this one writes into the chunk's buffer
 * directly and is handed to the channel without a copy.
 * <p>
 * For content which is already a file or a stream there is nothing to write at all - Netty's
 * {@link io.netty.handler.stream.ChunkedFile}, {@link io.netty.handler.stream.ChunkedNioFile} and
 * {@link io.netty.handler.stream.ChunkedStream} are {@link io.netty.handler.stream.ChunkedInput}s already,
 * and any of them can go straight to
 * {@link RestHandle.Result#ok(io.netty.util.AsciiString, io.netty.handler.stream.ChunkedInput)}.
 */
public interface ChunkedRestHandle {
    /**
     * Opens a cursor for one request. Everything which can fail on the request itself belongs here: nothing
     * has been sent yet, so an exception thrown from this still becomes an ordinary error response.
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
         * Writes the next part of the response - one record, a batch of them, whatever one step of the
         * underlying source is. The step size is what bounds the memory this response costs.
         * <p>
         * The buffer is the chunk itself and is handed to the channel as it is. It is already sized for a
         * whole chunk and grows if a step overshoots, so writing more than fits is allowed, it just costs
         * more memory.
         *
         * @param output to write into
         * @return false when the response is complete
         */
        boolean writeNext(ByteBuf output);

        /**
         * Releases whatever the cursor holds. Called exactly once, whether the response was completed, failed
         * or abandoned because the peer went away.
         */
        void close();
    }
}
