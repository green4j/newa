package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;

public class JsonErrorHandler extends AbstractApplicationJsonHandler implements ErrorHandler {
    private static final String ERROR = "error";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String MESSAGE = "message";
    private static final String STACKTRACE = "stacktrace";
    private static final String BY = "by";

    @Override
    public FullHttpResponseContent handle(final MethodNotAllowedException error) {
        return dumpRestExceptionNoStacktrace(error);
    }

    @Override
    public FullHttpResponseContent handle(final PathNotFoundException error) {
        return dumpRestExceptionNoStacktrace(error);
    }

    @Override
    public FullHttpResponseContent handle(final BadRequestException error) {
        return dumpRestExceptionNoStacktrace(error);
    }

    @Override
    public FullHttpResponseContent handle(final InternalServerErrorException error) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = jsonGenerator().start();
        dumpThrowableWithStacktrace(ERROR, error, output);
        return new DefaultFullHttpResponseContent(contentType, generator.finish());
    }

    private FullHttpResponseContent dumpRestExceptionNoStacktrace(final RestException error) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = jsonGenerator().start();
        output.startObject();
        output.objectMember(ERROR);
        output.stringValue(error.getClass().getName());

        if (error instanceof MethodNotAllowedException) {
            final String method = ((MethodNotAllowedException) error).method();
            if (method != null) {
                output.objectMember(METHOD);
                output.stringValue(method);
            }
        } else if (error instanceof PathNotFoundException) {
            final String path = ((PathNotFoundException) error).path();
            if (path != null) {
                output.objectMember(PATH);
                output.stringValue(path);
            }
        }

        final String message = error.getMessage();
        if (message != null) {
            output.objectMember(MESSAGE);
            output.stringValue(message, true);
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
