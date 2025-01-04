package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;

public class JsonErrorHandler extends AbstractApplicationJsonHandler implements ErrorHandler {

    @Override
    public void handle(final MethodNotAllowedException error,
                       final FullHttpResponseContent response) {
        fireExceptionNoStacktrace(error, response);
    }

    @Override
    public void handle(final PathNotFoundException error,
                       final FullHttpResponseContent response) {
        fireExceptionNoStacktrace(error, response);
    }

    @Override
    public void handle(final InternalServerErrorException error,
                       final FullHttpResponseContent response) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = jsonGenerator().start();
        output.startObject();
        output.objectMember("error");
        output.stringValue(error.getClass().getName());
        final String message = error.getMessage();
        if (message != null) {
            output.objectMember("message");
            output.stringValue(message, true);
        }
        output.objectMember("stacktrace");
        output.startArray();
        final StackTraceElement[] ste = error.getStackTrace();
        for (int i = 0; i < ste.length; i++) {
            output.stringValue(ste[i].toString(), true);
        }
        output.endArray();
        output.endObject();

        writeToResponse(generator.finish(), response);
    }

    private void fireExceptionNoStacktrace(final Exception error,
                                           final FullHttpResponseContent response) {
        final ByteArrayJsonGenerator generator = jsonGenerator();
        final JsonGenerator output = jsonGenerator().start();
        output.startObject();
        output.objectMember("error");
        output.stringValue(error.getClass().getName());
        final String message = error.getMessage();
        if (message != null) {
            output.objectMember("message");
            output.stringValue(message, true);
        }
        output.endObject();
        writeToResponse(generator.finish(), response);
    }

    private void writeToResponse(final ByteArray content,
                                 final FullHttpResponseContent response) {
        response.set(contentType,
                content.array(),
                content.start(),
                content.length());
    }
}
