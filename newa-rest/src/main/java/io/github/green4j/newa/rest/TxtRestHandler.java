package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ByteArray;
import io.github.green4j.newa.text.LineBuilder;
import io.netty.handler.codec.http.FullHttpRequest;

public class TxtRestHandler extends TextPlainRestHandler {
    private final TxtRestHandle handle;

    public TxtRestHandler(final TxtRestHandle handle) {
        this.handle = handle;
    }

    @Override
    protected final ByteArray doHandle(final FullHttpRequest request,
                                       final PathParameters pathParameters) {
        // to get rid of mem alloc?
        final LineBuilder output = new LineBuilder(); // TODO: another appendable + thread local
        handle.doHandle(request, pathParameters, output);
        final String txt = output.toString();
        final byte[] txtBytes = txt.getBytes();
        return new ByteArray() {
            @Override
            public byte[] array() {
                return txtBytes;
            }

            @Override
            public int start() {
                return 0;
            }

            @Override
            public int length() {
                return txtBytes.length;
            }
        };
    }
}
