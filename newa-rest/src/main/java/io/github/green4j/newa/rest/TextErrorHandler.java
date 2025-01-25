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
    public FullHttpResponseContent handle(final BadRequestException error) {
        return new DefaultFullHttpResponseContent(
                contentType,
                lineBuilder()
                        .append("Bad request: ")
                        .appendln(error.message())
                        .array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final InternalServerErrorException error) {
        final ByteArrayLineBuilder builder = lineBuilder();
        dumpThrowableWithStacktrace(
                "An error happened: ",
                0,
                error,
                builder
        );
        return new DefaultFullHttpResponseContent(
                contentType,
                builder.array()
        );
    }

    private static void dumpThrowableWithStacktrace(final String errorLabel,
                                                    final int level,
                                                    final Throwable error,
                                                    final ByteArrayLineBuilder output) {
        output.tab(level).append(errorLabel);
        output.appendln(error.toString());
        output.tab(level + 1).appendln("Stacktrace:");
        final StackTraceElement[] ste = error.getStackTrace();
        for (int i = 0; i < ste.length; i++) {
            output.tab(level + 2).appendln(ste[i].toString());
        }
        if (error.getCause() != null) {
            dumpThrowableWithStacktrace("By: ", level + 1, error.getCause(), output);
        }
    }
}
