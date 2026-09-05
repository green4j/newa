/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;

/**
 * Renders a JSON response and returns - the form {@code RestApiBuilder.getJson(...)} and its siblings
 * register. The generator writes into a buffer the thread reuses, so nothing is allocated per response, and
 * the response is sent once this returns.
 * <p>
 * What is thrown becomes the response: an {@link HttpException} carries its own status and message,
 * anything else is a {@code 500}.
 */
public interface JsonRestHandle {

    void doHandle(RestContext context,
                  JsonGenerator output) throws HttpException;

}
