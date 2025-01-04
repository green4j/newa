package io.github.green4j.newa.rest;

public interface ErrorHandler {

    void handle(MethodNotAllowedException error, FullHttpResponseContent response);

    void handle(PathNotFoundException error, FullHttpResponseContent response);

    void handle(InternalServerErrorException error, FullHttpResponseContent response);

}
