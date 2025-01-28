package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class MethodNotAllowedException extends RestException {
    static final long serialVersionUID = -1387516993124229947L;

    private final String method;

    public MethodNotAllowedException(final String method) {
        this(method, null);
    }

    public MethodNotAllowedException(final String method,
                                     final String message) {
        super(message);
        this.method = method;
    }

    public String method() {
        return method;
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.METHOD_NOT_ALLOWED;
    }

}
