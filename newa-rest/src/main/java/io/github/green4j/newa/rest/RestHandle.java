package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;

public interface RestHandle {

    void handle(FullHttpRequest request,
                PathParameters pathParameters,
                FullHttpResponse responseWriter) throws PathNotFoundException, InternalServerErrorException;

}
