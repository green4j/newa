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
        final ByteArrayLineBuilder text = lineBuilder()
                .append("Method not allowed");
        final String method = error.method();
        if (method != null) {
            text.append(": ").appendln(method);
        } else {
            text.appendln();
        }

        final String message = error.getMessage();
        if (message != null) {
            text.appendln(message);
        }

        return new DefaultFullHttpResponseContent(
                contentType,
                text.array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final PathNotFoundException error) {
        final ByteArrayLineBuilder text = lineBuilder()
                .append("Path not found");
        final String path = error.path();
        if (path != null) {
            text.append(": ").appendln(path);
        } else {
            text.appendln();
        }

        final String message = error.getMessage();
        if (message != null) {
            text.appendln(message);
        }

        return new DefaultFullHttpResponseContent(
                contentType,
                text.array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final BadRequestException error) {
        final ByteArrayLineBuilder text = lineBuilder()
                .append("Bad request");
        final String message = error.getMessage();
        if (message != null) {
            text.append(": ").appendln(message);
        } else {
            text.appendln();
        }

        return new DefaultFullHttpResponseContent(
                contentType,
                text.array()
        );
    }

    @Override
    public FullHttpResponseContent handle(final InternalServerErrorException error) {
        final ByteArrayLineBuilder text = lineBuilder();

        dumpThrowableWithStacktrace(
                "An error happened: ",
                0,
                error,
                text
        );

        return new DefaultFullHttpResponseContent(
                contentType,
                text.array()
        );
    }

    private static void dumpThrowableWithStacktrace(final String errorLabel,
                                                    final int level,
                                                    final Throwable error,
                                                    final ByteArrayLineBuilder text) {
        text.tab(level).append(errorLabel);
        text.appendln(error.toString());
        text.tab(level + 1).appendln("Stacktrace:");
        final StackTraceElement[] ste = error.getStackTrace();
        for (int i = 0; i < ste.length; i++) {
            text.tab(level + 2).appendln(ste[i].toString());
        }
        if (error.getCause() != null) {
            dumpThrowableWithStacktrace("By: ", level + 1, error.getCause(), text);
        }
    }
}
