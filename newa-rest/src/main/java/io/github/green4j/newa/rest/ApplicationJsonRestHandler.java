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
 * A {@link RestHandle} answering with {@code application/json} a subclass has rendered whole, into the thread's
 * growing buffer or anywhere else, and hands over as a {@link ByteArray}, which is copied into the response.
 * A subclass renders and may throw; what it throws becomes the error response.
 * <p>
 * For a document rendered per request, {@link JsonRestHandler} is the cheaper shape: it writes the document
 * into the channel's buffers as it goes, so no thread keeps a buffer the size of the largest document.
 */
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
        } finally {
            // the content has been copied into the response buffer by now, so an oversized
            // rendering buffer can be shrunk rather than retained by this thread forever
            responseRendered();
        }
    }

    protected abstract ByteArray doHandle(RestContext context)
            throws HttpException;
}