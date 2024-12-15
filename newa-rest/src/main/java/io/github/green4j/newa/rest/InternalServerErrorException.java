package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class InternalServerErrorException extends RestException {
    static final long serialVersionUID = -2387516993124229947L;

    private final HttpResponseStatus status;

    public InternalServerErrorException(final Exception error) {
        this(error, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    public InternalServerErrorException(final String message) {
        this(message, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    public InternalServerErrorException(final String message,
                                        final HttpResponseStatus status) {
        super(message);
        this.status = status;
    }

    public InternalServerErrorException(final Exception error,
                                        final HttpResponseStatus status) {
        super(error);
        this.status = status;
    }

    @Override
    public HttpResponseStatus status() {
        return status;
    }
}
