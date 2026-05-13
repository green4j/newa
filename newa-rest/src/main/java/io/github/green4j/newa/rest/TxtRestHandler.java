package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;

public class TxtRestHandler extends TextPlainRestHandler {
    private final TxtRestHandle handle;

    public TxtRestHandler(final TxtRestHandle handle) {
        this.handle = handle;
    }

    public TxtRestHandler(final Charset responseCharset,
                          final TxtRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    protected final ByteArray doHandle(final RestContext context) throws
            PathNotFoundException, BadRequestException {
        final ByteArrayLineBuilder lineBuilder = lineBuilder();
        handle.doHandle(context, lineBuilder);
        return lineBuilder.array();
    }
}
