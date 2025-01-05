package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;

public class JsonErrorHandler extends AbstractApplicationJsonHandler implements ErrorHandler {

    @Override
    public FullHttpResponseContent handle(final MethodNotAllowedException error) {
        return fireExceptionNoStacktrace(error);
    }

    @Override
    public FullHttpResponseContent handle(final PathNotFoundException error) {
        return fireExceptionNoStacktrace(error);
    }

    @Override
    public FullHttpResponseContent handle(final InternalServerErrorException error) {
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

        return new DefaultFullHttpResponseContent(contentType, generator.finish());
    }

    private FullHttpResponseContent fireExceptionNoStacktrace(final Exception error) {
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
        return new DefaultFullHttpResponseContent(contentType, generator.finish());
    }
}
