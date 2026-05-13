package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;

public interface TxtRestHandle {

    void doHandle(RestContext context,
                  LineAppendable output) throws PathNotFoundException, BadRequestException;

}
