/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;

/**
 * Wraps a {@link TxtRestHandle} into the handler an api is built from. This is what
 * {@code RestApiBuilder.getTxt(...)} registers.
 */
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
    protected final ByteArray doHandle(final RestContext context) throws HttpException {
        final ByteArrayLineBuilder lineBuilder = lineBuilder();
        handle.doHandle(context, lineBuilder);
        return lineBuilder.array();
    }
}
