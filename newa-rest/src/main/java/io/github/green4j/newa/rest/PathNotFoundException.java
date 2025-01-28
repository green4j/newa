package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;

public class PathNotFoundException extends RestException {
    static final long serialVersionUID = -3387516993124229933L;

    private final String path;

    public PathNotFoundException(final String path) {
        this(path, null);
    }

    public PathNotFoundException(final String path,
                                 final String message) {
        super(message);
        this.path = path;
    }

    public String path() {
        return path;
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.NOT_FOUND;
    }
}
