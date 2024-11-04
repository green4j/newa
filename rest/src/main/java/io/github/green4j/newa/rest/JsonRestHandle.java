package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.netty.handler.codec.http.FullHttpRequest;

public interface JsonRestHandle {

    void doHandle(FullHttpRequest request,
                  PathParameters pathParameters,
                  JsonGenerator output);

}
