package io.github.green4j.newa.rest;

public interface ErrorHandler {

    void handle(MethodNotAllowedException error, FullHttpResponse response);

    void handle(PathNotFoundException error, FullHttpResponse response);

    void handle(InternalServerErrorException error, FullHttpResponse response);

}
