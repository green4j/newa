package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public abstract class RestException extends Exception {
    static final long serialVersionUID = -3387516993124229947L;

    protected RestException() {
    }

    public abstract HttpResponseStatus status();
}
