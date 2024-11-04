package io.github.green4j.newa.json;

import io.github.green4j.jelly.AppendableWriter;
import io.github.green4j.jelly.JsonGenerator;

/**
 * The class supports escaping.
 */
public class AppendableWritingJsonGenerator {
    private final AppendableWriter<StringBuilder> result = new AppendableWriter<>(new StringBuilder());
    private final JsonGenerator generator = new JsonGenerator();

    public AppendableWritingJsonGenerator() {
        generator.setOutput(result);
    }

    public AppendableWriter<StringBuilder> finish() {
        generator.eoj();
        return result;
    }

    public JsonGenerator start() {
        result.output().setLength(0);
        generator.reset();
        return generator;
    }
}
