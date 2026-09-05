/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;

/**
 * A green-jelly {@link JsonGenerator} writing into a byte array which is reused document after document:
 * {@link #start()} clears the buffer and hands out the generator, {@link #finish()} ends the document and
 * returns the bytes written. Output is compact - no whitespace on the wire.
 */
public class ByteArrayJsonGenerator {
    /**
     * Compact rather than green-jelly's indented default. Whitespace on the wire costs bandwidth on every
     * response and buys nothing a client wants: a document meant to be read by a person is piped through
     * something which formats it anyway.
     */
    private final JsonGenerator generator = new JsonGenerator(false);
    private final ClearableByteArrayBufferingWriter writer;

    public ByteArrayJsonGenerator(final ClearableByteArrayBufferingWriter writer) {
        this.writer = writer;
        generator.setOutput(writer);
    }

    public ByteArray finish() {
        generator.eoj();
        return writer;
    }

    public JsonGenerator start() {
        writer.clear();
        generator.reset();
        return generator;
    }

    /**
     * The buffer grows to the largest document ever generated and {@link #start()} never shrinks it back,
     * so a caller which pools generators reads this to decide whether one is worth keeping.
     *
     * @return number of bytes the underlying buffer occupies
     */
    public int capacity() {
        final byte[] array = writer.array();
        return array == null ? 0 : array.length;
    }

    /**
     * @return number of bytes written: the document generated so far, or the last one generated if
     *         {@link #start()} has not been called since
     */
    public int length() {
        return writer.length();
    }
}
