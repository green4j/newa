package io.github.green4j.newa.rest;

public final class Endpoint {
    public static final String[] EMPTY = new String[0];
    private final String pathExpression;
    private final RestHandle handle;

    private String description;
    private String[] pathParameterDescriptions;
    private String[] queryParameterDescriptions;

    Endpoint(final String pathExpression,
                     final RestHandle handle) {
        this.pathExpression = pathExpression;
        this.handle = handle;
    }

    public Endpoint withDescription(final String description) {
        this.description = description;
        return this;
    }

    public Endpoint withPathParameterDescriptions(final String... parameterDescriptions) {
        this.pathParameterDescriptions = parameterDescriptions;
        return this;
    }

    public Endpoint withQueryParameterDescriptions(final String... queryParameterDescriptions) {
        this.queryParameterDescriptions = queryParameterDescriptions;
        return this;
    }

    public String pathExpression() {
        return pathExpression;
    }

    public RestHandle handle() {
        return handle;
    }

    public String description() {
        return description;
    }

    public String[] pathParameterDescriptions() {
        return pathParameterDescriptions != null ? pathParameterDescriptions : EMPTY;
    }

    public String[] queryParameterDescriptions() {
        return queryParameterDescriptions != null ? queryParameterDescriptions : EMPTY;
    }

    String pathExpressionWithoutQuery() {
        if (pathExpression == null) {
            return null;
        }
        final int qIdx = pathExpression.indexOf('?');
        return qIdx == -1 ? pathExpression : pathExpression.substring(0, qIdx);
    }
}
