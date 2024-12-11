package io.github.green4j.newa.rest;

public class JsonErrorHandler implements ErrorHandler {
    //private final

    @Override
    public void handle(final MethodNotAllowedException error,
                       final FullHttpResponse response) {

    }

    @Override
    public void handle(final PathNotFoundException error,
                       final FullHttpResponse response) {

    }

    @Override
    public void handle(final InternalServerErrorException error,
                       final FullHttpResponse response) {

    }
}
