package io.github.green4j.newa.rest;

import io.github.green4j.newa.json.AppendableWritingJsonGenerator;
import io.github.green4j.newa.lang.ByteArray;
import io.github.green4j.jelly.AppendableWriter;
import io.github.green4j.jelly.JsonGenerator;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;

public abstract class ApplicationJsonRestHandler implements RestHandle {
    protected static final ThreadLocal<AppendableWritingJsonGenerator> WRITING_GENERATOR =
            ThreadLocal.withInitial(() -> new AppendableWritingJsonGenerator());

    @Override
    public final void handle(final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final FullHttpResponse responseWriter) throws InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            responseWriter.setContent(
                    APPLICATION_JSON,
                    content.array(),
                    content.start(),
                    content.length()
            );
        } catch (final Exception e) {
            final AppendableWritingJsonGenerator writingGenerator =
                    WRITING_GENERATOR.get();
            final JsonGenerator output = writingGenerator.start(); // reset just in case
            output.startObject();
            output.objectMember("error");
            output.stringValue(e.getClass().getName());
            final String message = e.getMessage();
            if (message != null) {
                output.objectMember("message");
                output.stringValue(message, true);
            }
            output.objectMember("stacktrace");
            output.startArray();
            final StackTraceElement[] ste = e.getStackTrace();
            for (int i = 0; i < ste.length; i++) {
                output.stringValue(ste[i].toString(), true);
            }
            output.endArray();
            output.endObject();

            final AppendableWriter<StringBuilder> content = writingGenerator.finish();
            throw new InternalServerErrorException(
                    APPLICATION_JSON,
                    content.output()
            );
        }
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters) throws InternalServerErrorException;
}