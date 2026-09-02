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
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

/**
 * A response body which is not pulled but pushed: it has nothing to send until something happens - a clock
 * ticks, a queue gets an item, a feed produces an event - and it never runs out on its own. The peer is what
 * ends it, by going away.
 * <p>
 * {@link #next} answers null when there is nothing yet, which suspends the transfer rather than ending it.
 * Whatever produces the content resumes it by flushing the channel:
 * <pre>{@code
 * private void onTick() {
 *     due = true;
 *     channel.flush();   // wakes the transfer, which asks next() again
 * }
 * }</pre>
 * Between the two the connection costs no thread at all - a scheduled task, a subscription, and one buffer
 * when there is finally something to send.
 * <p>
 * Everything a {@link ChunkedInput} has to answer and cannot answer differently is answered here: how long
 * the response is (nobody knows), whether it has ended (not by itself), how far it has got (counted for you,
 * which is what {@link ResponseChunks#stallTimeoutMillis()} watches), and the overload Netty deprecated but
 * still declares. What is left is {@link #next} and {@link #close()}.
 * <p>
 * Such a response is outside the cursor accounting: {@link ResponseChunks#maxOpenCursors()} counts what
 * {@link ChunkedRestHandler} and its siblings open, not what is handed to
 * {@link RestHandle.Result#ok(io.netty.util.AsciiString, ChunkedInput)} directly.
 */
public abstract class PushedResponseBody implements ChunkedInput<ByteBuf> {
    private long progress;

    /**
     * Produces the next piece of the response, if there is one to produce right now.
     *
     * @param allocator to take the buffer from
     * @return the next piece, or null if there is nothing yet - the transfer then waits for a flush rather
     *         than ending
     */
    protected abstract ByteBuf next(ByteBufAllocator allocator);

    /**
     * Releases whatever the source holds - a scheduled task, a subscription. Called exactly once, whether the
     * peer went away, the response failed, or the watchdog gave up on it.
     */
    @Override
    public abstract void close();

    @Override
    public final ByteBuf readChunk(final ByteBufAllocator allocator) {
        final ByteBuf chunk = next(allocator);
        if (chunk != null) {
            progress += chunk.readableBytes();
        }
        return chunk;
    }

    // ChunkedInput declares this one abstract and deprecated at the same time, so the method and the
    // annotation both have to be here - and here is the only place anyone using this library needs them
    @Deprecated
    @Override
    public final ByteBuf readChunk(final ChannelHandlerContext ctx) {
        return readChunk(ctx.alloc());
    }

    @Override
    public final boolean isEndOfInput() {
        return false; // it is the peer, never the source, that ends a pushed response
    }

    @Override
    public final long length() {
        return -1; // that is the point: nobody knows, and nobody ever will
    }

    @Override
    public final long progress() {
        return progress;
    }
}
