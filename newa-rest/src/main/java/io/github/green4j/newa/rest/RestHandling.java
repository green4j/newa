package io.github.green4j.newa.rest;

public class RestHandling {
    private final RestHandle handle;
    private final PathParameters pathParameters;

    public RestHandling(final RestHandle handle,
                        final PathParameters pathParameters) {
        this.handle = handle;
        this.pathParameters = pathParameters;
    }

    public RestHandle handle() {
        return handle;
    }

    public PathParameters pathParameters() {
        return pathParameters;
    }
}
