package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.netty.handler.codec.http.FullHttpRequest;

public class Json_Gc implements JsonRestHandle {
    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final JsonGenerator output) {
        System.gc();
        output.stringValue("SUCCESS");
    }
}