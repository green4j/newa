package io.github.green4j.newa.rest;

public interface PathParameters {

    int numberOfParameters();

    String parameterName(int idx);

    CharSequence parameterValue(int idx);

    CharSequence parameterValue(final String name);

    CharSequence parameterValueRequired(final String name) throws BadRequestException;

    String parameterValueRequiredString(final String name) throws BadRequestException;
}
