package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ByteArray;
import io.github.green4j.newa.text.LineFormatter;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

public abstract class TextPlainRestHandler implements RestHandle {
    @Override
    public final void handle(final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final FullHttpResponse responseWriter) throws InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            responseWriter.setContent(
                    TEXT_PLAIN,
                    content.array(),
                    content.start(),
                    content.length()
            );
        } catch (final Exception e) {
            final LineFormatter errorText = new LineFormatter();
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
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters) throws InternalServerErrorException;
}
