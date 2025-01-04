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
                             final FullHttpResponseContent responseContent,
                             final Result result)
            throws PathNotFoundException, InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            responseContent.set(
                    contentType,
                    content.array(),
                    content.start(),
                    content.length()
            );
            result.ok();
        } catch (final Exception e) {
            result.error(e);
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, InternalServerErrorException;
}
