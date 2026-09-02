/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
