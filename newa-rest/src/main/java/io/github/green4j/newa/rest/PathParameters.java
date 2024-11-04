package io.github.green4j.newa.rest;

public interface PathParameters {

    int numberOfParameters();

    String parameterName(int idx);

    CharSequence parameterValue(int idx);

    default CharSequence parameterValue(final String name) {
        for (int i = 0; i < numberOfParameters(); i++) {
            if (CharSequence.compare(name, parameterName(i)) == 0) {
                return parameterValue(i);
            }
        }
        return null;
    }
}
