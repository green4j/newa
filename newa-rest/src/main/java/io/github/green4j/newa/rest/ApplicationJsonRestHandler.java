package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;

public abstract class ApplicationJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    protected ApplicationJsonRestHandler() {
    }

    protected ApplicationJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        try {
            final ByteArray content = doHandle(context);
            result.ok(new DefaultFullHttpResponseContent(contentType, content));
        } catch (final Exception e) {
            result.error(e);
        }
    }

    protected abstract ByteArray doHandle(RestContext context)
            throws PathNotFoundException, BadRequestException;
}