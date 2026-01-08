package io.github.green4j.newa.rest.handles;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.rest.JsonRestHandle;
import io.github.green4j.newa.rest.PathParameters;
import io.netty.handler.codec.http.FullHttpRequest;

public class Json_Execute implements JsonRestHandle {
    private final Runnable runnable;

    public Json_Execute(final Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final JsonGenerator output) {
        runnable.run();
        output.stringValue("OK");
    }
}
