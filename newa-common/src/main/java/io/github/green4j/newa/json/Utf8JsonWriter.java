/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Writes a JSON document encoded in UTF-8 into a {@link ByteBuf} of the allocator given - a websocket frame
 * to send or to fan out, a message to write to a channel. The document is generated with green-jelly's
 * {@link JsonGenerator}, compact, into a buffer of the thread, and copied out once into a buffer exactly its
 * size: no {@link String} of it and no heap buffer of its own.
 * <p>
 * The generator, and the buffer it writes into, is one per thread and reused document after document. A
 * document which grows the buffer past {@link #RETAINED_BUFFER_BYTES} is written the same way, but the buffer
 * is not kept, so one large document does not leave every thread which ever wrote one holding a buffer that
 * size.
 * A write started from the rendering of another write on the same thread gets a generator of its own.
 * <p>
 * Nothing is allocated from the allocator until the document has been generated, so a rendering which
 * throws leaves no buffer behind.
 */
public final class Utf8JsonWriter {
    /**
     * The largest buffer a thread keeps for the next document.
     */
    public static final int RETAINED_BUFFER_BYTES = 64 * 1024;

    private static final int INITIAL_BUFFER_SIZE = 1024;

    private static final ThreadLocal<Writer> WRITER = ThreadLocal.withInitial(Writer::new);

    private Utf8JsonWriter() {
    }

    /**
     * Writes the document the rendering generates.
     *
     * @param allocator to take the buffer from
     * @param render generates the document
     * @return the document, which the caller owns
     */
    public static ByteBuf write(final ByteBufAllocator allocator,
                                final Consumer<JsonGenerator> render) {
        return write(allocator, render, Consumer::accept);
    }

    /**
     * Writes the document the rendering generates from the source given. A rendering which captures nothing
     * - a method reference to a static method, a lambda using its arguments only - is one object for all the
     * documents, so a document written this way costs the buffer it is written into and nothing else.
     *
     * @param allocator to take the buffer from
     * @param source what the document is generated from
     * @param render generates the document from the source
     * @param <T> the type of the source
     * @return the document, which the caller owns
     */
    public static <T> ByteBuf write(final ByteBufAllocator allocator,
                                    final T source,
                                    final BiConsumer<? super T, JsonGenerator> render) {
        final Writer writer = acquire();
        try {
            render.accept(source, writer.generator.start());
            final ByteArray document = writer.generator.finish();
            final int length = document.length();
            return allocator.buffer(length).writeBytes(document.array(), document.start(), length);
        } finally {
            release(writer);
        }
    }

    // what the next write on this thread is generated with, for the tests to see whether it was kept
    static Object retainedGenerator() {
        return WRITER.get().generator;
    }

    private static Writer acquire() {
        final Writer writer = WRITER.get();
        if (writer.busy) {
            return new Writer(); // a write from the rendering of the write in progress
        }
        writer.busy = true;
        return writer;
    }

    private static void release(final Writer writer) {
        if (writer == WRITER.get()) {
            writer.busy = false;
            // the buffer, not the document: a rendering which throws has grown it all the same
            if (writer.generator.capacity() > RETAINED_BUFFER_BYTES) {
                WRITER.remove(); // the buffer it has grown goes with it
            }
        }
    }

    private static final class Writer {
        private final ByteArrayJsonGenerator generator =
                new ByteArrayJsonGenerator(new Utf8ByteArrayWriter(INITIAL_BUFFER_SIZE));

        private boolean busy;
    }
}
