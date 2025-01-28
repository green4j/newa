package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class BadRequestException extends RestException {
    static final long serialVersionUID = -3387516993124229933L;

    public BadRequestException(final String message) {
        super(message);
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.BAD_REQUEST;
    }
}
