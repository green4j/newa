/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Something failed rather than answered: {@code 500}, always, around the exception which caused it.
 * <p>
 * This is the one error whose details never leave the process. Its message is the cause's
 * {@code toString()} - a type of the implementation and whatever that type had to say, a file path as often
 * as not - and its stack trace names the classes the server is built from. A default {@link HttpErrorHandler}
 * renders nothing of that: the client is told the status and no more, and the cause goes to
 * {@link HttpObserver#onResponseFailed} instead, where a log can have it.
 * <p>
 * A {@code 500} which is a deliberate answer rather than a failure is not this: throw
 * {@code new HttpException(INTERNAL_SERVER_ERROR, "...")} and the message is rendered, because a message
 * written by hand is one the author meant the client to read.
 */
public class InternalServerErrorException extends HttpException {
    private static final long serialVersionUID = -2387516993124229947L;

    /**
     * @param error which failed the request
     */
    public InternalServerErrorException(final Throwable error) {
        super(HttpResponseStatus.INTERNAL_SERVER_ERROR, error);
    }
}
