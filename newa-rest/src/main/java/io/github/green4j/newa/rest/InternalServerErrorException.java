package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class InternalServerErrorException extends RestException {
    static final long serialVersionUID = -2387516993124229947L;

    private final Exception error;
    private final HttpResponseStatus status;

    public InternalServerErrorException(final Exception error) {
        this(error, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    public InternalServerErrorException(final Exception error,
                                        final HttpResponseStatus status) {
        this.error = error;
        this.status = status;
    }

    @Override
    public HttpResponseStatus status() {
        return status;
    }

    public Exception error() {
        return error;
    }
}
