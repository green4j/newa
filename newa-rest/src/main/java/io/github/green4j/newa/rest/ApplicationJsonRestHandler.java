package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public abstract class ApplicationJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    protected ApplicationJsonRestHandler() {
    }

    protected ApplicationJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final ChannelHandlerContext ctx,
                             final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final Result result)
            throws PathNotFoundException, InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            result.ok(new DefaultFullHttpResponseContent(contentType, content));
        } catch (final Exception e) {
            result.error(e);
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, InternalServerErrorException;
}