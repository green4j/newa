/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;

/**
 * Errors as {@code application/json}: {@code error} is the status, {@code path} or {@code method} what it was
 * about, {@code message} what the error carries.
 * <p>
 * An {@link InternalServerErrorException} is answered with its status and nothing else - see
 * {@link TextErrorHandler} for why, and {@link HttpObserver#onResponseFailed} for where the cause goes
 * instead. Every other {@link HttpException} carries a message written by hand, and that is rendered.
 * <p>
 * {@link #disclosingInternals()} turns the failure into a full dump - class, message, {@code stacktrace},
 * and every cause under {@code by} - which is for development only.
 */
public class JsonErrorHandler extends AbstractApplicationJsonHandler implements HttpErrorHandler {
    private static final String ERROR = "error";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String MESSAGE = "message";
    private static final String STACKTRACE = "stacktrace";
    private static final String BY = "by";

    /**
     * @return one which renders a failure in full, stack trace and causes included. For development only:
     *         it hands whoever asked the shape of the process
     */
    public static JsonErrorHandler disclosingInternals() {
        return new JsonErrorHandler(true);
    }

    private final boolean disclosingInternals;

    public JsonErrorHandler() {
        this(false);
    }

    private JsonErrorHandler(final boolean disclosingInternals) {
        this.disclosingInternals = disclosingInternals;
    }

    @Override
    public FullHttpResponseContent handle(final HttpException error) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = generator.start();

        if (error instanceof InternalServerErrorException && disclosingInternals) {
            dumpThrowableWithStacktrace(ERROR, error, output);
            return new DefaultFullHttpResponseContent(contentType, generator.finish());
        }

        output.startObject();
        output.objectMember(ERROR);
        output.stringValue(error.status().reasonPhrase(), true);

        if (error instanceof PathNotFoundException) {
            final String path = ((PathNotFoundException) error).path();
            if (path != null) {
                output.objectMember(PATH);
                output.stringValue(path, true);
            }
        } else if (error instanceof MethodNotAllowedException) {
            final String method = ((MethodNotAllowedException) error).method();
            if (method != null) {
                output.objectMember(METHOD);
                output.stringValue(method, true);
            }
        }

        if (!(error instanceof InternalServerErrorException)) {
            // the message of a failure is the cause's toString(), which names a type of the implementation
            final String message = error.getMessage();
            if (message != null) {
                output.objectMember(MESSAGE);
                output.stringValue(message, true);
            }
        }

        output.endObject();
        return new DefaultFullHttpResponseContent(contentType, generator.finish());
    }

    private static void dumpThrowableWithStacktrace(final String errorObjectMember,
                                                    final Throwable error,
                                                    final JsonGenerator output) {
        output.startObject();
        output.objectMember(errorObjectMember);
        output.stringValue(error.getClass().getName());
        final String message = error.getMessage();
        if (message != null) {
            output.objectMember(MESSAGE);
            output.stringValue(message, true);
        }
        output.objectMember(STACKTRACE);
        output.startArray();
        final StackTraceElement[] ste = error.getStackTrace();
        for (int i = 0; i < ste.length; i++) {
            output.stringValue(ste[i].toString(), true);
        }
        output.endArray();
        if (error.getCause() != null) {
            dumpThrowableWithStacktrace(BY, error.getCause(), output);
        }
        output.endObject();
    }
}
