/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The request reached an endpoint and is malformed: {@code 400}. The message is rendered into the response,
 * so it says what the client got wrong and nothing about the process.
 */
public class BadRequestException extends HttpException {
    private static final long serialVersionUID = -3387516993124229933L;

    public BadRequestException(final String message) {
        super(HttpResponseStatus.BAD_REQUEST, message);
    }
}
