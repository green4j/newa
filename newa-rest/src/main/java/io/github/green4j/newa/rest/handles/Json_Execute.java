package io.github.green4j.newa.rest.handles;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.rest.JsonRestHandle;
import io.github.green4j.newa.rest.RestContext;

public class Json_Execute implements JsonRestHandle {
    private final Runnable runnable;

    public Json_Execute(final Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public void doHandle(final RestContext context,
                         final JsonGenerator output) {
        runnable.run();
        output.stringValue("OK");
    }
}
