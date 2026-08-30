package io.github.green4j.newa.rest;

public class RestHandling {
    private final RestHandle handle;
    private final NamedValues pathParameters;
    private final String pathExpression;

    public RestHandling(final RestHandle handle,
                        final NamedValues pathParameters,
                        final String pathExpression) {
        this.handle = handle;
        this.pathParameters = pathParameters;
        this.pathExpression = pathExpression;
    }

    public RestHandle handle() {
        return handle;
    }

    /**
     * @return the expression the matched endpoint was declared with, {@code /v1/rows/{count}} rather than
     *         {@code /v1/rows/17}
     */
    public String pathExpression() {
        return pathExpression;
    }

    public NamedValues pathParameters() {
        return pathParameters;
    }
}
