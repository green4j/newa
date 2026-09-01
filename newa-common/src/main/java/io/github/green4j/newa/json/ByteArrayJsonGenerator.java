package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;

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
     * Size of the underlying buffer. The buffer grows to fit the largest document ever generated and
     * {@link #start()} never shrinks it back, so a caller which pools generators can use this to decide
     * whether a generator is worth keeping.
     *
     * @return number of bytes the underlying buffer occupies
     */
    public int capacity() {
        final byte[] array = writer.array();
        return array == null ? 0 : array.length;
    }

    /**
     * Length of the document generated so far, or of the last one generated if {@link #start()} has not been
     * called since.
     *
     * @return number of bytes written
     */
    public int length() {
        return writer.length();
    }
}
