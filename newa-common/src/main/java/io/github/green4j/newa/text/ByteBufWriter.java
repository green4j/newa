/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.lang.Charset;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Objects;

/**
 * Writes text - JSON, lines, or anything else written through green-jelly's byte-array writers - into buffers of
 * a Netty allocator, to be sent as they are. Whatever the size of the text, it is never held whole on the heap
 * and never copied whole: it is written into an array of the thread, and whenever that is full, what it holds
 * goes into a buffer of the allocator and the writing goes on in the array. Each byte is copied once, from the
 * array into a buffer.
 * <p>
 * A thread has one writer per charset, reused text after text, so the heap a text costs is that array and
 * nothing else, whatever its size. The array starts small and doubles each time a text fills it, up to
 * 256 KiB, and is kept at that size: an event loop serving large responses holds the
 * largest array, a thread which publishes small frames a small one. The bytes in flight are the allocator's:
 * with the default allocator, direct memory, bounded by {@code -XX:MaxDirectMemorySize} or
 * {@code io.netty.maxDirectMemory}.
 * <pre>{@code
 * final ByteBufWriter writer = ByteBufWriter.start(Charset.UTF8, allocator);
 * try {
 *     render(writer.json());
 *     return writer.finish();
 * } finally {
 *     writer.abort(); // releases what was taken if the rendering threw; nothing after finish()
 * }
 * }</pre>
 * A text which fits the array is one buffer exactly its size. A larger one is a {@link CompositeByteBuf} of a
 * buffer per array-full, never consolidated, since merging its components would copy the text.
 * <p>
 * A caller which does not {@link #abort()} in a {@code finally} leaks the buffers taken so far, and leaves the
 * thread's writer looking busy: direct memory running out is an {@link OutOfMemoryError}, not an
 * {@link Exception}.
 * <p>
 * Between {@link #start} and {@link #finish()} or {@link #abort()} the writer is the caller's. A text started
 * on a thread whose writer is in use - written from within the writing of another - gets a writer of its own.
 */
public final class ByteBufWriter {
    /**
     * The size an array starts at: what a thread which only ever writes small texts keeps.
     */
    static final int INITIAL_ARRAY_BYTES = 1024;

    /**
     * The size an array grows to and stays at: the most heap a text costs, and the size of the buffers a large
     * text is made of.
     */
    static final int MAX_ARRAY_BYTES = 256 * 1024;

    private static final ThreadLocal<ByteBufWriter> ASCII =
            ThreadLocal.withInitial(() -> new ByteBufWriter(Charset.US_ASCII, INITIAL_ARRAY_BYTES, MAX_ARRAY_BYTES));
    private static final ThreadLocal<ByteBufWriter> UTF8 =
            ThreadLocal.withInitial(() -> new ByteBufWriter(Charset.UTF8, INITIAL_ARRAY_BYTES, MAX_ARRAY_BYTES));

    private final ClearableByteArrayBufferingWriter writer;

    private JsonGenerator json;
    private ByteArrayLineBuilder lines;
    private boolean jsonStarted;

    private ByteBufAllocator allocator; // not null while a text is being written
    private ByteBuf first; // the text, while it is one buffer
    private CompositeByteBuf composite; // the text, once it is more
    private long spilled;

    ByteBufWriter(final Charset charset,
                  final int initialCapacity,
                  final int maxCapacity) {
        // the writers read their array again after a spill, so one may be put in its place there
        writer = charset == Charset.US_ASCII
                ? new AsciiByteArrayWriter(initialCapacity) {
                    @Override
                    protected boolean spill(final int roomSize) {
                        drain();
                        final int grown = grown(array().length, maxCapacity);
                        if (grown > array().length) {
                            set(new byte[grown]);
                        }
                        return true;
                    }
                }
                : new Utf8ByteArrayWriter(initialCapacity) {
                    @Override
                    protected boolean spill(final int roomSize) {
                        drain();
                        final int grown = grown(array().length, maxCapacity);
                        if (grown > array().length) {
                            set(new byte[grown]);
                        }
                        return true;
                    }
                };
    }

    private static int grown(final int capacity,
                             final int maxCapacity) {
        return capacity >= maxCapacity ? capacity : (int) Math.min(maxCapacity, 2L * capacity);
    }

    /**
     * Begins a text.
     *
     * @param charset the text is encoded in
     * @param allocator to take the buffers of the text from
     * @return the thread's writer for the charset, or one of its own if that is in use
     */
    public static ByteBufWriter start(final Charset charset,
                                      final ByteBufAllocator allocator) {
        final ByteBufWriter thread = (charset == Charset.US_ASCII ? ASCII : UTF8).get();
        return (thread.allocator == null ? thread : new ByteBufWriter(charset, INITIAL_ARRAY_BYTES, MAX_ARRAY_BYTES))
                .begin(allocator);
    }

    ByteBufWriter begin(final ByteBufAllocator allocator) {
        // not null is what tells a writer in use from an idle one
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        writer.clear();
        return this;
    }

    /**
     * @return what the text is written into, for a {@link JsonGenerator} or a {@link ByteArrayLineBuilder} of
     *         the caller's own: one which lives longer than a text, such as a generator writing a document a
     *         chunk at a time
     */
    public ClearableByteArrayBufferingWriter output() {
        return writer;
    }

    /**
     * @return a compact generator writing a JSON document into this text; {@link #finish()} closes whatever it
     *         leaves open
     */
    public JsonGenerator json() {
        if (json == null) {
            json = new JsonGenerator(false); // compact, as everywhere newa generates JSON
        }
        json.setOutput(writer);
        json.reset();
        jsonStarted = true;
        return json;
    }

    /**
     * @return lines written into this text
     */
    public ByteArrayLineBuilder lines() {
        if (lines == null) {
            lines = new ByteArrayLineBuilder(writer);
        }
        return lines;
    }

    /**
     * @return the bytes written since {@link #start}
     */
    public long length() {
        return spilled + writer.length();
    }

    /**
     * Ends the text and hands it over. The writer is not the caller's any more.
     *
     * @return the text, which the caller owns; an empty buffer if nothing was written
     */
    public ByteBuf finish() {
        if (jsonStarted) {
            json.eoj(); // closes every scope left open, then flushes
        }
        drain();

        final ByteBuf text = composite != null ? composite
                : first != null ? first
                : Unpooled.EMPTY_BUFFER;
        first = null;
        composite = null;
        end();
        return text;
    }

    /**
     * Releases the text begun: for a writing which failed. Nothing to do once the text has been finished or
     * aborted, so it may be called in a {@code finally} after {@link #finish()}.
     */
    public void abort() {
        if (allocator == null) {
            return;
        }
        try {
            if (composite != null) {
                composite.release();
            } else if (first != null) {
                first.release();
            }
        } finally {
            first = null;
            composite = null;
            end();
        }
    }

    private void end() {
        allocator = null;
        spilled = 0;
        jsonStarted = false;
        writer.clear();
    }

    private void drain() {
        final int length = writer.length();
        if (length == 0) {
            return;
        }
        // exactly the bytes there are: a small text must not take a buffer the size of the array
        final ByteBuf part = allocator.buffer(length).writeBytes(writer.array(), writer.start(), length);
        writer.clear();
        spilled += length;

        if (first == null && composite == null) {
            first = part;
            return;
        }
        try {
            if (composite == null) {
                // no consolidation: merging the components would copy the text they hold
                composite = allocator.compositeBuffer(Integer.MAX_VALUE);
                final ByteBuf one = first;
                first = null;
                composite.addComponent(true, one); // releases it if it cannot be added
            }
        } catch (final Throwable e) {
            part.release();
            throw e;
        }
        composite.addComponent(true, part); // likewise
    }
}
