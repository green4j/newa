package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;

public class TextErrorHandler extends AbstractTextPlainHandler implements ErrorHandler {
    public TextErrorHandler() {
    }

    public TextErrorHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public void handle(final MethodNotAllowedException error,
                       final FullHttpResponseContent response) {
        writeToResponse(
                lineBuilder()
                        .append("Method not allowed: ")
                        .appendln(error.method()),
                response
        );
    }

    @Override
    public void handle(final PathNotFoundException error,
                       final FullHttpResponseContent response) {
        writeToResponse(
                lineBuilder()
                        .append("Path not found: ")
                        .appendln(error.path()),
                response
        );
    }

    @Override
    public void handle(final InternalServerErrorException error,
                       final FullHttpResponseContent response) {
        final ByteArrayLineBuilder builder = lineBuilder()
                .append("An error happened: ");
        final String message = error.getMessage();
        if (message != null) {
            builder.appendln(message);
        } else {
            final Throwable cause = error.getCause();
            if (cause != null) {
                builder.appendln(cause.toString());
            } else {
                builder.appendln(error.toString());
            }
        }
        builder.appendln("Stacktrace:");
        final StackTraceElement[] ste = error.getStackTrace();
        for (int i = 0; i < ste.length; i++) {
            builder.append("    ").appendln(ste[i].toString());
        }
        writeToResponse(builder, response);
    }

    private void writeToResponse(final ByteArrayLineBuilder builder,
                                 final FullHttpResponseContent response) {
        final ByteArray content = builder.array();
        response.set(contentType,
                content.array(),
                content.start(),
                content.length());
    }
}
