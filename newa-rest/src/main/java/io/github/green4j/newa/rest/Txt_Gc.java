package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

public class Txt_Gc implements TxtRestHandle {
    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineAppendable output) {
        System.gc();
        output.append("System GC triggered");
    }
}