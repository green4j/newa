package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ByteArray;
import io.github.green4j.newa.text.LineAppendable;
import io.github.green4j.newa.text.LineBuilder;
import io.netty.handler.codec.http.FullHttpRequest;

import java.nio.charset.StandardCharsets;

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
                    // to get rid of mem alloc?
                    final LineBuilder output = new LineBuilder(); // TODO: another appendable + thread local
                    doHandle(output);
                    final String txt = output.toString();
                    final byte[] bytes = txt.getBytes(StandardCharsets.UTF_8);

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

    protected abstract void doHandle(LineAppendable output);
}
