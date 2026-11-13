/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;

/**
 * A {@link RestHandle} answering with {@code text/plain} a subclass has rendered whole and hands over as a
 * {@link ByteArray} - the {@link ApplicationJsonRestHandler} of the text side, and, like it, the dearer shape
 * next to {@link TxtRestHandler}.
 */
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
            throws HttpException;
}
