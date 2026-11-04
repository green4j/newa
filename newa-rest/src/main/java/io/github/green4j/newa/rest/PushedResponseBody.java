/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

/**
 * A response body which is not pulled but pushed: it has nothing to send until something happens - a clock
 * ticks, a queue gets an item, a feed produces an event, a decoder on a thread of its own finishes the next
 * piece. It ends when the source says so with {@link #end()}, or when the peer goes away.
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
 * the response is (nobody knows), whether it has ended (once {@link #next} has run dry after {@link #end()}),
 * how far it has got (counted for you, which is what an observer is told), and the overload Netty deprecated
 * but still declares. What is left is {@link #next} and {@link #close()}.
 * <p>
 * Such a response is outside the cursor accounting: {@link ResponseChunks#maxOpenCursors()} counts what
 * {@link ChunkedRestHandler} and its siblings open, not what is handed to
 * {@link RestHandle.Result#ok(io.netty.util.AsciiString, ChunkedInput)} directly.
 */
public abstract class PushedResponseBody implements ChunkedInput<ByteBuf> {
    private long progress;

    private volatile boolean endRequested;

    private boolean ended;

    /**
     * Produces the next piece of the response, if there is one to produce right now.
     *
     * @param allocator to take the buffer from
     * @return the next piece, or null if there is nothing yet - the transfer then waits for a flush rather
     *         than ending, unless {@link #end()} has been called
     */
    protected abstract ByteBuf next(ByteBufAllocator allocator);

    /**
     * Ends the response once {@link #next} has nothing more to give: what it still gives out goes first. Safe
     * to call from any thread. It wakes nothing by itself, so flush the channel after it, the same as after
     * any other piece produced.
     */
    protected final void end() {
        endRequested = true;
    }

    /**
     * Releases whatever the source holds - a scheduled task, a subscription. Called exactly once, whether the
     * response was ended, the peer went away, the response failed, or the watchdog gave up on it.
     */
    @Override
    public abstract void close();

    @Override
    public final ByteBuf readChunk(final ByteBufAllocator allocator) {
        if (ended) {
            return null;
        }
        final boolean endWasRequested = endRequested; // read before next(): a piece produced along with the
        // request is visible to next() by then, so a null from it is really the last word
        final ByteBuf chunk = next(allocator);
        if (chunk != null) {
            progress += chunk.readableBytes();
            return chunk;
        }
        if (endWasRequested) {
            ended = true;
            // HttpChunkedInput asks whether the input has ended before it asks for a chunk, so the end is
            // taken up only by a chunk answered after it - an empty one, which costs nothing on the wire
            return Unpooled.EMPTY_BUFFER;
        }
        return null;
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
        return ended;
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
