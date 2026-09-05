/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.handles;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.rest.JsonRestHandle;
import io.github.green4j.newa.rest.RestContext;

/**
 * Runs something and answers {@code "ok"} - a {@code /shutdown} or a {@code /gc} endpoint in one line. The
 * runnable runs on the event loop of the request, so it must not block; whatever it throws becomes an error
 * response like any other.
 */
public class JsonExecute implements JsonRestHandle {
    private final Runnable runnable;

    public JsonExecute(final Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public void doHandle(final RestContext context,
                         final JsonGenerator output) {
        runnable.run();
        output.stringValue("ok");
    }
}
