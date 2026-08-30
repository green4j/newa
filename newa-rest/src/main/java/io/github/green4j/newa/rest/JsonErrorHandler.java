package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.netty.handler.codec.http.HttpResponseStatus;

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
        if (!HttpResponseStatus.INTERNAL_SERVER_ERROR.equals(error.status())) {
            // this exception also carries deliberate answers - a 503 when the server is at its limit, say.
            // Those are not crashes, and a stack trace of the code which decided to send one says nothing
            return dumpRestExceptionNoStacktrace(error);
        }
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = generator.start();
        dumpThrowableWithStacktrace(ERROR, error, output);
        return new DefaultFullHttpResponseContent(contentType, generator.finish());
    }

    private FullHttpResponseContent dumpRestExceptionNoStacktrace(final RestException error) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = generator.start();
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
