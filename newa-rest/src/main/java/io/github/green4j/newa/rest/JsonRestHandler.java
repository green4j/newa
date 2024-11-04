package io.github.green4j.newa.rest;

import io.github.green4j.jelly.AppendableWriter;
import io.github.green4j.newa.json.AppendableWritingJsonGenerator;
import io.github.green4j.newa.lang.ByteArray;
import io.netty.handler.codec.http.FullHttpRequest;

import java.nio.charset.StandardCharsets;

public class JsonRestHandler extends ApplicationJsonRestHandler {

    private final JsonRestHandle handle;

    public JsonRestHandler(final JsonRestHandle handle) {
        this.handle = handle;
    }

    @Override
    protected ByteArray doHandle(final FullHttpRequest request, final PathParameters pathParameters) {
        final AppendableWritingJsonGenerator writingGenerator =
                WRITING_GENERATOR.get();
        handle.doHandle(request, pathParameters, writingGenerator.start());
        final AppendableWriter<StringBuilder> content = writingGenerator.finish();
        final byte[] bytes = content.output().toString().getBytes(StandardCharsets.US_ASCII);
        return new ByteArray() {
            @Override
            public byte[] array() {
                return bytes;
            }

            @Override
            public int start() {
                return 0;
            }

            @Override
            public int length() {
                return bytes.length;
            }
        };
    }
}
