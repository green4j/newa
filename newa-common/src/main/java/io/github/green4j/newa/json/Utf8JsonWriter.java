/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteBufWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Writes a JSON document encoded in UTF-8 into buffers of the allocator given - a websocket frame to send or to
 * fan out, a message to write to a channel. The document is generated with green-jelly's {@link JsonGenerator},
 * compact, through the {@link ByteBufWriter} of the thread: no {@link String} of it, and on the heap nothing but
 * the thread's array of at most 256 KiB, whatever the size of the document. A document which fits that array is
 * one buffer exactly its size.
 * <p>
 * A rendering which throws gives back to the allocator whatever it has taken.
 */
public final class Utf8JsonWriter {
    private Utf8JsonWriter() {
    }

    /**
     * Writes the document the rendering generates.
     *
     * @param allocator to take the buffers from
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
     * documents, so a document written this way costs the buffers it is written into and nothing else.
     *
     * @param allocator to take the buffers from
     * @param source what the document is generated from
     * @param render generates the document from the source
     * @param <T> the type of the source
     * @return the document, which the caller owns
     */
    public static <T> ByteBuf write(final ByteBufAllocator allocator,
                                    final T source,
                                    final BiConsumer<? super T, JsonGenerator> render) {
        final ByteBufWriter writer = ByteBufWriter.start(Charset.UTF8, allocator);
        try {
            render.accept(source, writer.json());
            return writer.finish();
        } finally {
            writer.abort(); // the buffers taken so far, if the rendering threw; nothing once finished
        }
    }
}
