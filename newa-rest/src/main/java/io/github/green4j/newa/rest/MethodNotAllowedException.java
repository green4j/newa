/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The path is served, but not by this method: {@code 405}.
 */
public class MethodNotAllowedException extends HttpException {
    private static final long serialVersionUID = -1387516993124229947L;

    private final String method;

    public MethodNotAllowedException(final String method) {
        this(method, null);
    }

    public MethodNotAllowedException(final String method,
                                     final String message) {
        super(HttpResponseStatus.METHOD_NOT_ALLOWED, message);
        this.method = method;
    }

    public String method() {
        return method;
    }
}
