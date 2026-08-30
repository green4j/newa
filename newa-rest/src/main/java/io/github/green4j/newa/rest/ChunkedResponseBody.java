package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

/**
 * A response body pulled a chunk at a time. {@link io.netty.handler.stream.ChunkedWriteHandler} asks for a
 * chunk, writes it, and asks for the next one - but stops asking while the channel is over its write
 * watermark and resumes when it drains. That is the whole of the backpressure: the producer is a cursor which
 * is simply not stepped, so a peer which stops reading costs a suspended cursor and one buffer, not a thread.
 */
abstract class ChunkedResponseBody implements ChunkedInput<ByteBuf> {
    private final ResponseChunks chunks;
    private final RestApiObserver observer;
    private final long openedAt;

    private boolean exhausted;
    private boolean stalled;
    private boolean closed;
    private long progress;

    ChunkedResponseBody(final RestContext context) {
        this.chunks = context.responseChunks();
        this.observer = context.observer();
        this.openedAt = observing() ? System.nanoTime() : 0;
    }

    /**
     * Steps the cursor until the chunk is full or the document is complete, calling {@link #markExhausted()}
     * in the latter case.
     *
     * @param allocator to take the chunk's buffer from
     * @return the chunk, empty if the document ended exactly on a chunk boundary
     */
    abstract ByteBuf renderChunk(ByteBufAllocator allocator);

    /**
     * Releases the cursor. Called exactly once.
     */
    abstract void closeCursor();

    final ResponseChunks chunks() {
        return chunks;
    }

    final void markExhausted() {
        exhausted = true;
    }

    /**
     * Reports that the response is being given up on because its peer took nothing for too long, so that the
     * outcome says so rather than looking like an ordinary disconnect.
     */
    void markStalled() {
        stalled = true;
    }

    @Override
    public final boolean isEndOfInput() {
        return exhausted;
    }

    // ChunkedInput declares this one abstract and deprecated at the same time, so it has to be here and
    // the annotation has to be here with it - without it -Werror stops the build. The other one does the work
    @Deprecated
    @Override
    public final ByteBuf readChunk(final ChannelHandlerContext ctx) {
        return readChunk(ctx.alloc());
    }

    @Override
    public final ByteBuf readChunk(final ByteBufAllocator allocator) {
        if (exhausted) {
            return null;
        }

        // never null below: null means "nothing yet, ask again later", and a cursor which is done is never
        // going to produce that chunk, so ChunkedWriteHandler would wait for it forever. An empty chunk, on
        // the other hand, encodes to nothing at all
        final ByteBuf chunk = renderChunk(allocator);
        final int bytes = chunk.readableBytes();
        progress += bytes;

        if (observing()) {
            observer.onChunkWritten(bytes);
        }
        return chunk;
    }

    @Override
    public final long length() {
        return -1; // that is the point: nobody knows until the cursor is done
    }

    @Override
    public final long progress() {
        return progress;
    }

    /**
     * Releases the cursor and its slot. Several things race to be the one that ends a response - the write
     * completing, the write failing, the channel going away, the watchdog giving up - so this has to be safe
     * to call from all of them and reach the cursor exactly once. They all run on the event loop, so a plain
     * flag is enough.
     */
    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            closeCursor();
        } finally {
            final int left = chunks.cursorClosed();
            if (observing()) {
                observer.onCursorClosed(
                        left,
                        progress,
                        System.nanoTime() - openedAt,
                        outcome()
                );
            }
        }
    }

    private RestApiObserver.Outcome outcome() {
        if (exhausted) {
            return RestApiObserver.Outcome.COMPLETED;
        }
        return stalled ? RestApiObserver.Outcome.STALLED : RestApiObserver.Outcome.ABANDONED;
    }

    private boolean observing() {
        return observer != null;
    }
}
