package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.Arrays;

public abstract class LazyStaticJsonRestHandler extends ApplicationJsonRestHandler {
    private volatile ByteArray content;

    protected LazyStaticJsonRestHandler() {
    }

    protected LazyStaticJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    protected final ByteArray doHandle(final FullHttpRequest request,
                                       final PathParameters pathParameters) {
        if (content == null) {
            synchronized (this) {
                if (content == null) {
                    final ByteArrayJsonGenerator generator = jsonGenerator();
                    doHandle(generator.start());
                    final ByteArray result = generator.finish();
                    final byte[] arrayCopy = Arrays.copyOfRange(
                            result.array(),
                            result.start(),
                            result.start() + result.length()
                    );
                    content = new ByteArray() {
                        @Override
                        public byte[] array() {
                            return arrayCopy;
                        }

                        @Override
                        public int start() {
                            return 0;
                        }

                        @Override
                        public int length() {
                            return arrayCopy.length;
                        }
                    };
                }
            }
        }
        return content;
    }

    protected abstract void doHandle(JsonGenerator output);
}
