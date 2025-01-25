package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;

public interface RestRouter {

    RestHandling resolve(FullHttpRequest request) throws
            MethodNotAllowedException,
            PathNotFoundException;
}
