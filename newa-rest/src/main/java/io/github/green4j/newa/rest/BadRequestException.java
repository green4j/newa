package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class BadRequestException extends RestException {
    static final long serialVersionUID = -3387516993124229933L;

    private final String message;

    public BadRequestException(final String message) {
        this.message = message;
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.BAD_REQUEST;
    }

    public String message() {
        return message;
    }
}
