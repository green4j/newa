package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;

public interface HttpHandler {

    void handle(FullHttpRequest request,
                FullHttpResponse responseWriter) throws
            MethodNotAllowedException,
            PathNotFoundException,
            InternalServerErrorException;

}
