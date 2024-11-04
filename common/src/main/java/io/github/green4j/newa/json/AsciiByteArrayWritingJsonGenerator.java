package io.github.green4j.newa.json;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.JsonGenerator;

/**
 * IMPORTANT: The class doesn't support escaping.
 */
public class AsciiByteArrayWritingJsonGenerator {
    private final AsciiByteArrayWriter result = new AsciiByteArrayWriter(1024);
    private final JsonGenerator generator = new JsonGenerator();

    public AsciiByteArrayWritingJsonGenerator() {
        generator.setOutput(result);
    }

    public AsciiByteArrayWriter finish() {
        generator.eoj();
        return result;
    }

    public JsonGenerator start() {
        result.clear();
        generator.reset();
        return generator;
    }
}
