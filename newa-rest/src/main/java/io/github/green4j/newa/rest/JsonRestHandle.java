package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;

public interface JsonRestHandle {

    void doHandle(RestContext context,
                  JsonGenerator output) throws PathNotFoundException, BadRequestException;

}
