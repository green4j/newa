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

package io.github.green4j.newa.rest.handles;

import io.github.green4j.newa.rest.Endpoint;
import io.github.green4j.newa.rest.LazyStaticTxtRestHandler;
import io.github.green4j.newa.rest.Method;
import io.github.green4j.newa.rest.RestApiHelpFactory;
import io.github.green4j.newa.rest.RestApiParameters;
import io.github.green4j.newa.text.LineAppendable;

import static io.github.green4j.newa.rest.handles.Util.appendlnNotNullable;

public class TxtHelp extends LazyStaticTxtRestHandler {
    public static RestApiHelpFactory factory() {
        return TxtHelp::new;
    }

    private final RestApiParameters restApiParameters;

    TxtHelp(final RestApiParameters restApiParameters) {
        this.restApiParameters = restApiParameters;
    }

    @Override
    protected void doHandle(final LineAppendable output) {
        boolean hasMetaInfo = false;

        hasMetaInfo |= appendlnNotNullable(output, "name", restApiParameters.name());
        hasMetaInfo |= appendlnNotNullable(output, "description", restApiParameters.description());
        hasMetaInfo |= appendlnNotNullable(output, "version", restApiParameters.fullVersion());
        hasMetaInfo |= appendlnNotNullable(output, "build version", restApiParameters.buildVersion());

        final int totalSize = restApiParameters.endpoints().length;

        if (totalSize > 0) {
            for (final Method method : restApiParameters.methods()) {
                final Endpoint[] endpoints = method.endpoints();

                if (endpoints.length == 0) {
                    continue;
                }

                if (hasMetaInfo) {
                    output.tab(1);
                }
                output.appendln(method.name());

                for (int i = 0; i < endpoints.length; i++) {
                    final Endpoint ep = endpoints[i];
                    if (i > 0) {
                        output.appendln();
                    }

                    if (hasMetaInfo) {
                        output.tab(2);
                    } else {
                        output.tab(1);
                    }
                    output.appendln(ep.pathExpression());
                    if (ep.description() != null) {
                        if (hasMetaInfo) {
                            output.tab(3);
                        } else {
                            output.tab(2);
                        }
                        output.appendln(ep.description());
                    }

                    final String[] pathParamDescriptions = ep.pathParameterDescriptions();
                    if (pathParamDescriptions.length > 0) {
                        output.tab(3).appendln("path parameters: ");
                        for (int p = 0; p < pathParamDescriptions.length; p++) {
                            output.tab(4).appendln(pathParamDescriptions[p]);
                        }
                    }

                    final String[] queryParamDescriptions = ep.queryParameterDescriptions();
                    if (queryParamDescriptions.length > 0) {
                        output.tab(3).appendln("query parameters: ");
                        for (int p = 0; p < queryParamDescriptions.length; p++) {
                            output.tab(4).appendln(queryParamDescriptions[p]);
                        }
                    }
                }
            }
        }
    }
}
