package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.text.LineAppendable;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.Arrays;

public abstract class LazyStaticTxtRestHandler extends TextPlainRestHandler {
    private volatile ByteArray content;

    protected LazyStaticTxtRestHandler() {
    }

    @Override
    protected final ByteArray doHandle(final FullHttpRequest request,
                                       final PathParameters pathParameters) {
        if (content == null) {
            synchronized (this) {
                if (content == null) {
                    final ByteArrayLineBuilder output = lineBuilder();
                    doHandle(output);
                    final ByteArray result = output.array();
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

    protected abstract void doHandle(LineAppendable output);
}
