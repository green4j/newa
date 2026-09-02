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
