/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Nothing serves the path: {@code 404}. The path is the one the request spelled, so anything rendering it
 * into markup has to escape it.
 */
public class PathNotFoundException extends HttpException {
    private static final long serialVersionUID = -3387516993124229933L;

    private final String path;

    public PathNotFoundException(final String path) {
        this(path, null);
    }

    public PathNotFoundException(final String path,
                                 final String message) {
        super(HttpResponseStatus.NOT_FOUND, message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
