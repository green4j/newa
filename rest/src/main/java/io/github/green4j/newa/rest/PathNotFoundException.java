package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class PathNotFoundException extends RestException {
    static final long serialVersionUID = -3387516993124229933L;

    public PathNotFoundException() {
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.NOT_FOUND;
    }
}
