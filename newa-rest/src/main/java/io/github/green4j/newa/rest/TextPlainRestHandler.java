package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;

public abstract class TextPlainRestHandler
        extends AbstractTextPlainHandler implements RestHandle {

    protected TextPlainRestHandler() {
    }

    protected TextPlainRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        try {
            final ByteArray content = doHandle(context);
            result.ok(contentType, content);
        } catch (final Exception e) {
            result.error(e);
        } finally {
            // the content has been copied into the response buffer by now, so an oversized
            // rendering buffer can be shrunk rather than retained by this thread forever
            responseRendered();
        }
    }

    protected abstract ByteArray doHandle(RestContext context)
            throws PathNotFoundException, BadRequestException;
}
