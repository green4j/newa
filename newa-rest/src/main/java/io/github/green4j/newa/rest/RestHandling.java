package io.github.green4j.newa.rest;

public class RestHandling {
    private final RestHandle handle;
    private final NamedValues pathParameters;

    public RestHandling(final RestHandle handle,
                        final NamedValues pathParameters) {
        this.handle = handle;
        this.pathParameters = pathParameters;
    }

    public RestHandle handle() {
        return handle;
    }

    public NamedValues pathParameters() {
        return pathParameters;
    }
}
