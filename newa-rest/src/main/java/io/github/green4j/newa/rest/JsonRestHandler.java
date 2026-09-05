/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;

/**
 * Wraps a {@link JsonRestHandle} into the handler an api is built from. This is what
 * {@code RestApiBuilder.getJson(...)} registers.
 */
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
            throws HttpException {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        handle.doHandle(context, generator.start());
        return generator.finish();
    }
}
