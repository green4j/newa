package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineFormatter;
import io.netty.handler.codec.http.FullHttpRequest;

public interface TxtRestHandle {

    void doHandle(FullHttpRequest request,
                  PathParameters pathParameters,
                  LineFormatter output);

}
