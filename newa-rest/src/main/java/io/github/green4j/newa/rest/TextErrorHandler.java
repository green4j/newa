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
                       final FullHttpResponse response) {
        writeToResponse(
                lineBuilder()
                        .append("Method not allowed: ")
                        .appendln(error.method()),
                response);
    }

    @Override
    public void handle(final PathNotFoundException error,
                       final FullHttpResponse response) {
        writeToResponse(
                lineBuilder()
                        .append("Path not found: ")
                        .appendln(error.path()),
                response);
    }

    @Override
    public void handle(final InternalServerErrorException error,
                       final FullHttpResponse response) {
        final ByteArrayLineBuilder builder = lineBuilder()
                .append("An error happened: ")
                .appendln(error.getLocalizedMessage());
        builder.appendln("Stacktrace:");
        for (final StackTraceElement ste : error.getStackTrace()) {
            builder.append("    ").append(ste.toString());
        }
        writeToResponse(builder, response);
    }

    private void writeToResponse(final ByteArrayLineBuilder builder,
                                 final FullHttpResponse response) {
        final ByteArray content = builder.array();
        response.setContent(contentType,
                content.array(),
                content.start(),
                content.length());
    }
}
