package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.FullHttpRequest;

public class JsonRestHandler extends ApplicationJsonRestHandler {
    private final JsonRestHandle handle;

    public JsonRestHandler(final JsonRestHandle handle) {
        this.handle = handle;
    }

    public JsonRestHandler(final Charset responseCharset,
                           final JsonRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    protected ByteArray doHandle(final FullHttpRequest request,
                                 final PathParameters pathParameters)
            throws PathNotFoundException, BadRequestException {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        handle.doHandle(request, pathParameters, generator.start());
        return generator.finish();
    }
}
