/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
