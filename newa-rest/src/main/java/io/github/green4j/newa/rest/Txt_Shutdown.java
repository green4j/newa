package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

public class Txt_Shutdown implements TxtRestHandle {
    private final Switch aSwitch;

    public Txt_Shutdown(final Switch aSwitch) {
        this.aSwitch = aSwitch;
    }

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineAppendable output) {
        output.append("bye");
        aSwitch.off(request.uri() + " called");
    }
}
