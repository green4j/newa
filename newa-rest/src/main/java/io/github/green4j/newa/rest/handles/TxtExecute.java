/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.handles;

import io.github.green4j.newa.rest.RestContext;
import io.github.green4j.newa.rest.TxtRestHandle;
import io.github.green4j.newa.text.LineAppendable;

/**
 * Runs something and answers {@code OK} in plain text, on the terms of {@link JsonExecute}.
 */
public class TxtExecute implements TxtRestHandle {
    private final Runnable runnable;

    public TxtExecute(final Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public void doHandle(final RestContext context,
                         final LineAppendable output) {
        runnable.run();
        output.append("OK");
    }
}
