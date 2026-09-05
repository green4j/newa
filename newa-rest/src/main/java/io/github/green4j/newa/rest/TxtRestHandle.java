/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;

/**
 * Renders a {@code text/plain} response and returns, on the terms of {@link JsonRestHandle} - the form
 * {@code RestApiBuilder.getTxt(...)} and its siblings register.
 */
public interface TxtRestHandle {

    void doHandle(RestContext context,
                  LineAppendable output) throws HttpException;

}
