package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;

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
    protected ByteArray doHandle(final RestContext context)
            throws PathNotFoundException, BadRequestException {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        handle.doHandle(context, generator.start());
        return generator.finish();
    }
}
