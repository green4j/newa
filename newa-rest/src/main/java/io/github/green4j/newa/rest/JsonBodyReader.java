/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.simple.JsonValue;

/**
 * What {@link RestContext#jsonBodyAs(JsonBodyReader)} makes of the body of a request: a value the body has
 * not got is the client's mistake here, and nowhere else.
 *
 * @param <T> what the body is read as
 */
@FunctionalInterface
public interface JsonBodyReader<T> {
    /**
     * @param body the value of the body of the request
     * @return what the body is read as
     * @throws HttpException to answer the request with a status of its own
     */
    T read(JsonValue body) throws HttpException;
}
