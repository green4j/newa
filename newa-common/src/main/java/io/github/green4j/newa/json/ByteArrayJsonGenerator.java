package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;

public class ByteArrayJsonGenerator {
    private final JsonGenerator generator = new JsonGenerator();
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
}
