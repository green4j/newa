/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * An error which is answered with an HTTP status, whatever produced it: routing, a handler, or the file
 * server. This is the whole contract an {@link HttpErrorHandler} renders, and the type a user's own exceptions
 * extend to reach it with a status of their own.
 * <p>
 * Anything thrown which is not one of these is a failure rather than an answer, and is wrapped in an
 * {@link InternalServerErrorException} - the one kind whose details never leave the process.
 */
public class HttpException extends Exception {
    private static final long serialVersionUID = -3387516993124229947L;

    private final transient HttpResponseStatus status;

    /**
     * @param status to answer with
     * @param message rendered into the response, so say only what the client may be told
     */
    public HttpException(final HttpResponseStatus status,
                         final String message) {
        super(message);
        this.status = status;
    }

    /**
     * @param status to answer with
     * @param cause of it. Note that this makes {@link #getMessage()} the cause's {@code toString()}, which
     *              names an internal type: see {@link InternalServerErrorException}
     */
    public HttpException(final HttpResponseStatus status,
                         final Throwable cause) {
        super(cause);
        this.status = status;
    }

    /**
     * @param status to answer with
     * @param message rendered into the response
     * @param cause of it
     */
    public HttpException(final HttpResponseStatus status,
                         final String message,
                         final Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * @return the status the response carries
     */
    public final HttpResponseStatus status() {
        return status;
    }
}
