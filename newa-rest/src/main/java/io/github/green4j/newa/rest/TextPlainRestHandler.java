package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.FullHttpRequest;

public abstract class TextPlainRestHandler
        extends AbstractTextPlainHandler implements RestHandle {

    protected TextPlainRestHandler() {
    }

    protected TextPlainRestHandler(final Charset responseCharset) {
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
        /*
        try {

        } catch (final Exception e) {
            final LineBuilder errorText = new LineBuilder();
            errorText.appendln("Internal Server Error");
            errorText.append("Message: ").appendln(e.getMessage());
            errorText.appendln("Stack Trace:");
            final StackTraceElement[] ste = e.getStackTrace();
            for (int i = 0; i < ste.length; i++) {
                errorText.append("  ").appendln(ste[i].toString());
            }
            throw new InternalServerErrorException(
                    TEXT_PLAIN,
                    errorText.toString()
            );
        }*/
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, InternalServerErrorException;
}
