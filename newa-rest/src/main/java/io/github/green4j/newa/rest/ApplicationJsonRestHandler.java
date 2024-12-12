package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.FullHttpRequest;

public abstract class ApplicationJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    protected ApplicationJsonRestHandler() {
    }

    protected ApplicationJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final FullHttpResponse responseWriter)
            throws PathNotFoundException, InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            responseWriter.setContent(
                    contentType,
                    content.array(),
                    content.start(),
                    content.length()
            );
        } catch (final PathNotFoundException | InternalServerErrorException e) {
            throw e;
        } catch (final Exception e) {
            throw new InternalServerErrorException(e);
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, InternalServerErrorException;
}