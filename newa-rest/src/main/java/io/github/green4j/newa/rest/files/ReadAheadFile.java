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

package io.github.green4j.newa.rest.files;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.EventExecutor;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.Executor;

/**
 * The body of a file which is not carried by {@code sendfile(2)}, read on a thread which is not the event
 * loop.
 * <p>
 * {@link io.netty.handler.stream.ChunkedNioFile}, which this stands in for, reads a chunk inside
 * {@link #readChunk(ByteBufAllocator)} - on the event loop, where a page which is not in the cache stalls
 * every other connection that loop carries, and where the chunk is then encrypted or compressed on the same
 * thread. Here the read happens on the {@link Executor} the server was given, and the loop is told when there
 * is something to write.
 * <p>
 * No thread is held for the length of a response. A chunk is read <em>ahead</em>, while the one before it is
 * being written: {@link #readChunk(ByteBufAllocator)} hands over what is ready and starts the next read, and
 * returns null while a read is in flight - which is what {@link ChunkedWriteHandler} suspends on, and what
 * {@link #resume()} wakes with {@link ChunkedWriteHandler#resumeTransfer()}. A thread of the executor is busy
 * for one {@code read(2)} and no longer, whatever the peer does with the rest of the response. The overlap is
 * worth something on its own: the next chunk is read while the socket is taking the current one.
 * <p>
 * Everything but the read itself happens on the event loop - the state below is touched nowhere else - and
 * the buffer a read filled is published by the very task which hands it over.
 * <p>
 * The alternative not taken: a {@link ChunkedWriteHandler} on an {@link io.netty.util.concurrent.EventExecutorGroup}
 * of its own is one line, but that handler stands in front of {@code FileServerHandler}, so every outbound
 * message of the channel - the responses of the api behind it included - would pay the hop, and a thread of
 * that group would be the one blocked on the disk.
 */
final class ReadAheadFile implements ChunkedInput<ByteBuf> {
    /** Nothing is being read and nothing is waiting to be written. */
    private static final int IDLE = 0;

    /** A read is running on the executor; the loop has been told to wait. */
    private static final int READING = 1;

    /** A chunk has been read and is waiting for the next {@link #readChunk(ByteBufAllocator)}. */
    private static final int READY = 2;

    /** A read threw, and the next {@link #readChunk(ByteBufAllocator)} is where that is reported. */
    private static final int FAILED = 3;

    /** Closed: the file is gone, or is about to be taken by the read which is still in flight. */
    private static final int CLOSED = 4;

    private final FileChannel file;
    private final Executor reads;
    private final EventExecutor loop;
    private final ChunkedWriteHandler writer;
    private final ByteBufAllocator allocator;
    private final int chunkSize;
    private final long startOffset;
    private final long endOffset;

    private long readOffset;
    private long delivered;
    private int state = IDLE;
    private ByteBuf ready;
    private Throwable failure;

    /**
     * @param ctx of the handler writing the response, for the allocator and the loop the state belongs to.
     * @param writer the response is queued in, to be woken when a chunk has been read.
     * @param reads the file is read on, which is anything but the event loop.
     * @param file open at the range below, and closed by {@link #close()} however this ends.
     * @param offset of the first byte to send.
     * @param length to send from it.
     * @param chunkSize read at a time, and held twice over at most while one chunk is written and the next
     *                  is read.
     */
    ReadAheadFile(final ChannelHandlerContext ctx,
                  final ChunkedWriteHandler writer,
                  final Executor reads,
                  final FileChannel file,
                  final long offset,
                  final long length,
                  final int chunkSize) throws IOException {
        if (!file.isOpen()) {
            throw new ClosedChannelException();
        }
        this.file = file;
        this.reads = reads;
        this.writer = writer;
        this.loop = ctx.executor();
        this.allocator = ctx.alloc();
        this.chunkSize = chunkSize;
        this.startOffset = offset;
        this.readOffset = offset;
        this.endOffset = offset + length;
    }

    /**
     * @return whether everything this was asked for has been handed to the pipeline. A chunk which has been
     *         read but not taken is not one of them, and neither is a file which turned out to be shorter
     *         than the response promised - that one ends here, with {@link #progress()} short of
     *         {@link #length()}, which is what closes the connection rather than leaving the peer reading a
     *         response which cannot end.
     */
    @Override
    public boolean isEndOfInput() {
        return ready == null && state != READING && readOffset >= endOffset;
    }

    /**
     * Gives back the file, and the chunk which was read into but never written. A read still in flight is
     * left the file it is reading from: it is the hand-over of that read which closes it, a moment later.
     */
    @Override
    public void close() {
        if (state == CLOSED) {
            return;
        }
        final boolean reading = state == READING;
        state = CLOSED;
        release();
        if (!reading) {
            closeFile();
        }
    }

    @Deprecated
    @Override
    public ByteBuf readChunk(final ChannelHandlerContext ctx) throws Exception {
        return readChunk(ctx.alloc());
    }

    /**
     * @param offered by the pipeline, and not used: the buffer is allocated by the thread which fills it,
     *                out of the allocator of the channel this response is on.
     * @return the chunk read ahead, or null while there is a read in flight - on which
     *         {@link ChunkedWriteHandler} waits to be resumed.
     * @throws Exception which a read threw, reported here because a read has nowhere else to report it.
     */
    @Override
    public ByteBuf readChunk(final ByteBufAllocator offered) throws Exception {
        switch (state) {
            case READY: {
                final ByteBuf chunk = ready;
                ready = null;
                state = IDLE;
                delivered += chunk.readableBytes();
                startRead(); // the next one is read while this one is written
                return chunk;
            }
            case IDLE:
                startRead();
                return null;
            case READING:
                return null;
            case FAILED:
                throw failed(failure);
            default:
                throw new ClosedChannelException();
        }
    }

    /**
     * @return how much of the file has been handed to the pipeline, which is what the response is judged
     *         complete by. Not how much has been read: a chunk read ahead of a connection which then died
     *         never reached anybody, and counting it would make a truncated response look whole.
     */
    @Override
    public long progress() {
        return delivered;
    }

    @Override
    public long length() {
        return endOffset - startOffset;
    }

    private void startRead() {
        if (readOffset >= endOffset) {
            return; // read to the end already: what is left is to hand over what was read
        }
        final int size = (int) Math.min(chunkSize, endOffset - readOffset);
        final long at = readOffset;
        readOffset = at + size;
        state = READING;
        try {
            reads.execute(() -> read(at, size));
        } catch (final RuntimeException rejected) {
            // nothing is going to run, so nothing is going to wake the loop either: fail the response here,
            // where ChunkedWriteHandler still closes this input and gives the file back
            readOffset = at;
            state = IDLE;
            throw rejected;
        }
    }

    /**
     * The one thing which does not happen on the event loop.
     *
     * @param at the first byte to read.
     * @param size to read from it.
     */
    private void read(final long at,
                      final int size) {
        ByteBuf chunk = null;
        Throwable failed = null;
        try {
            chunk = allocator.buffer(size, size);
            int done = 0;
            while (done < size) {
                final int read = chunk.writeBytes(file, at + done, size - done);
                if (read <= 0) {
                    // shorter than it was when it was measured - and a file closed under the read reads the
                    // same way, since that is what Netty makes of a closed channel here. Both end the
                    // response short, which is what closes the connection
                    break;
                }
                done += read;
            }
        } catch (final Throwable error) {
            failed = error;
        }

        final ByteBuf read = chunk;
        final Throwable error = failed;
        try {
            loop.execute(() -> handOver(read, error));
        } catch (final RuntimeException rejected) {
            // the loop is gone and this task is the last thing holding either of them
            if (read != null) {
                read.release();
            }
            closeFile();
        }
    }

    /**
     * Where a read comes back to the loop, and where what it read becomes visible to it.
     *
     * @param chunk which was read, or null if the read never got one.
     * @param error the read failed with, or null.
     */
    private void handOver(final ByteBuf chunk,
                          final Throwable error) {
        if (state == CLOSED) {
            // closed under the read: the file was left open for it, and this is where both of them go
            if (chunk != null) {
                chunk.release();
            }
            closeFile();
            return;
        }

        if (error != null) {
            if (chunk != null) {
                chunk.release();
            }
            failure = error;
            state = FAILED;
        } else if (chunk.isReadable()) {
            ready = chunk;
            state = READY;
        } else {
            // the file ended before the length which was promised: nothing more will be read from it
            chunk.release();
            readOffset = endOffset;
            state = IDLE;
        }

        resume();
    }

    /**
     * Wakes the transfer which was suspended when there was nothing to write yet.
     */
    private void resume() {
        writer.resumeTransfer();
    }

    private void release() {
        if (ready != null) {
            ready.release();
            ready = null;
        }
    }

    private void closeFile() {
        try {
            file.close();
        } catch (final IOException ignored) {
            // the response is already over, one way or another, and there is nobody left to tell
        }
    }

    private static Exception failed(final Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new IOException("Reading the file failed", cause);
    }
}
