package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.lang.Charset;
import io.netty.handler.codec.http.FullHttpRequest;

public abstract class ApplicationJsonRestHandler
        extends AbstractApplicationJsonHandler implements RestHandle {

    protected ApplicationJsonRestHandler() {
    }

    protected ApplicationJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    public final void handle(final FullHttpRequest request,
                             final PathParameters pathParameters,
                             final FullHttpResponse responseWriter)
            throws PathNotFoundException, InternalServerErrorException {
        try {
            final ByteArray content = doHandle(request, pathParameters);
            responseWriter.setContent(
                    contentType,
                    content.array(),
                    content.start(),
                    content.length()
            );
        } catch (final PathNotFoundException | InternalServerErrorException e) {
            throw e;
        } catch (final Exception e) {
            throw new InternalServerErrorException(e);
        }

        /*
        try {

        } catch (final Exception e) {
            final AppendableWritingJsonGenerator writingGenerator =
                    ASCII_WRITING_GENERATOR.get();
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
         */
    }

    protected abstract ByteArray doHandle(FullHttpRequest request,
                                          PathParameters pathParameters)
            throws PathNotFoundException, InternalServerErrorException;
}