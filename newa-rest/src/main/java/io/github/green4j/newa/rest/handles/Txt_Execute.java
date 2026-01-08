package io.github.green4j.newa.rest.handles;

import io.github.green4j.newa.rest.PathParameters;
import io.github.green4j.newa.rest.TxtRestHandle;
import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

public class Txt_Execute implements TxtRestHandle {
    private final Runnable runnable;

    public Txt_Execute(final Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineAppendable output) {
        runnable.run();
        output.append("OK");
    }
}
