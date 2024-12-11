package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

public interface TxtRestHandle {

    void doHandle(FullHttpRequest request,
                  PathParameters pathParameters,
                  LineAppendable output) throws PathNotFoundException, InternalServerErrorException;

}
