/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * What one request resolved to: the handle to run, the path parameters it matched with, and the expression
 * it matched against.
 * <p>
 * The parameters are a flyweight over the matcher of the resolving thread and are overwritten by the next
 * request that thread resolves - {@link #pathExpression()} is the part which outlives the request, and the
 * label a metric wants.
 */
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
