package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;

public class TextErrorHandler extends AbstractTextPlainHandler implements ErrorHandler {
    public TextErrorHandler() {
    }

    public TextErrorHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public FullHttpResponseContent handle(final MethodNotAllowedException error) {
        return new DefaultFullHttpResponseContent(
                contentType,
                lineBuilder()
                        .append("Method not allowed: ")
                        .appendln(error.method())
                        .array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final PathNotFoundException error) {
        return new DefaultFullHttpResponseContent(
                contentType,
                lineBuilder()
                        .append("Path not found: ")
                        .appendln(error.path())
                        .array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final InternalServerErrorException error) {
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
        return new DefaultFullHttpResponseContent(
                contentType,
                builder.array()
        );
    }
}
