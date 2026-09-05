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
 * A {@link RestHandle} which renders {@code application/json} into the thread's buffer and answers with it
 * inside {@code handle} - the shape every JSON endpoint of an api ends up in. A subclass renders and may
 * throw; what it throws becomes the error response.
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