package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.netty.handler.codec.http.FullHttpRequest;

public class Json_Shutdown implements JsonRestHandle {
    private final Switch aSwitch;

    public Json_Shutdown(final Switch aSwitch) {
        this.aSwitch = aSwitch;
    }

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final JsonGenerator output) {
        output.stringValue("bye");
        aSwitch.off(request.uri() + " called");
    }
}
