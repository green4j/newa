package io.github.green4j.newa.rest;

public interface PathParameters {

    int numberOfParameters();

    String parameterName(int idx);

    CharSequence parameterValue(int idx);

    CharSequence parameterValue(String name);

    CharSequence parameterValueRequired(String name) throws BadRequestException;

    String parameterValueRequiredString(String name) throws BadRequestException;
}
