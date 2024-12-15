package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public abstract class RestException extends Exception {
    static final long serialVersionUID = -3387516993124229947L;

    protected RestException() {
    }

    protected RestException(final String message) {
        super(message);
    }

    protected RestException(final Throwable cause) {
        super(cause);
    }

    protected RestException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public abstract HttpResponseStatus status();
}
