package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public abstract class TextPlainRestHandler
        extends AbstractTextPlainHandler implements RestHandle {

    protected TextPlainRestHandler() {
    }

    protected TextPlainRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final ChannelHandlerContext ctx,
                             final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final Result result) {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            result.ok(contentType, content);
        } catch (final Exception e) {
            result.error(e);
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, BadRequestException;
}
