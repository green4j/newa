package io.github.green4j.newa.rest;

import io.github.green4j.jelly.AppendableWriter;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.AppendableWritingJsonGenerator;
import io.github.green4j.newa.lang.ByteArray;
import io.netty.handler.codec.http.FullHttpRequest;

import java.nio.charset.StandardCharsets;

public abstract class LazyStaticJsonRestHandler extends ApplicationJsonRestHandler {
    private volatile ByteArray content;

    protected LazyStaticJsonRestHandler() {
    }

    @Override
    protected final ByteArray doHandle(final FullHttpRequest request,
                                       final PathParameters pathParameters) {
        if (content == null) {
            synchronized (this) {
                if (content == null) {
                    final AppendableWritingJsonGenerator writingGenerator =
                            WRITING_GENERATOR.get();
                    doHandle(writingGenerator.start());
                    final AppendableWriter<StringBuilder> contentWriter = writingGenerator.finish();

                    // make a copy of mutable array
                    final byte[] bytes = contentWriter.output().toString().getBytes(StandardCharsets.UTF_8);

                    content = new ByteArray() {
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
        }
        return content;
    }

    protected abstract void doHandle(JsonGenerator output);
}
