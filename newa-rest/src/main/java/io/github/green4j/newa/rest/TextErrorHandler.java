/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteArrayLineBuilder;

/**
 * Errors as {@code text/plain}: the status, what it was about - the path, the method - and the message the
 * error carries.
 * <p>
 * An {@link InternalServerErrorException} is answered with its status and nothing else. Its message is the
 * failure's {@code toString()} and its stack trace names the classes the server is built from; neither is the
 * client's business. The cause reaches a log through {@link HttpObserver#onResponseFailed} instead.
 * Every other {@link HttpException} carries a message written by hand, and that is rendered.
 * <p>
 * {@link #disclosingInternals()} turns the failure into a full dump - class, message, stack trace, every
 * cause - which is what a development machine wants and what a server facing anyone else must not do.
 */
public class TextErrorHandler extends AbstractTextPlainHandler implements HttpErrorHandler {

    /**
     * @return one which renders a failure in full, stack trace and causes included. For development only:
     *         it hands whoever asked the shape of the process
     */
    public static TextErrorHandler disclosingInternals() {
        return new TextErrorHandler(Charset.UTF8, true);
    }

    /**
     * @param responseCharset to render in
     * @return one which renders a failure in full, stack trace and causes included. For development only
     */
    public static TextErrorHandler disclosingInternals(final Charset responseCharset) {
        return new TextErrorHandler(responseCharset, true);
    }

    private final boolean disclosingInternals;

    public TextErrorHandler() {
        this(Charset.UTF8, false);
    }

    public TextErrorHandler(final Charset responseCharset) {
        this(responseCharset, false);
    }

    private TextErrorHandler(final Charset responseCharset,
                             final boolean disclosingInternals) {
        super(responseCharset);
        this.disclosingInternals = disclosingInternals;
    }

    @Override
    public FullHttpResponseContent handle(final HttpException error) {
        final ByteArrayLineBuilder text = lineBuilder();

        if (error instanceof InternalServerErrorException) {
            if (disclosingInternals) {
                dumpThrowableWithStacktrace("An error happened: ", 0, error, text);
            } else {
                text.appendln(error.status().reasonPhrase());
            }
            return new DefaultFullHttpResponseContent(
                    contentType,
                    text.array()
            );
        }

        text.append(error.status().reasonPhrase());

        final String about = about(error);
        if (about != null) {
            text.append(": ").appendln(about);
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

    /**
     * @param error being rendered
     * @return what it was about - the path nothing served, the method nothing allowed - or null when the
     *         status says all there is
     */
    private static String about(final HttpException error) {
        if (error instanceof PathNotFoundException) {
            return ((PathNotFoundException) error).path();
        }
        if (error instanceof MethodNotAllowedException) {
            return ((MethodNotAllowedException) error).method();
        }
        return null;
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
