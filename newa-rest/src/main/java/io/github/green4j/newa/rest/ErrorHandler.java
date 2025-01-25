package io.github.green4j.newa.rest;

public interface ErrorHandler {

    FullHttpResponseContent handle(MethodNotAllowedException error);

    FullHttpResponseContent handle(PathNotFoundException error);

    FullHttpResponseContent handle(BadRequestException error);

    FullHttpResponseContent handle(InternalServerErrorException error);

}
