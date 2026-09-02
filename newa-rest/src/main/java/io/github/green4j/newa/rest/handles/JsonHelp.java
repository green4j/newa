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

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.rest.Endpoint;
import io.github.green4j.newa.rest.LazyStaticJsonRestHandler;
import io.github.green4j.newa.rest.Method;
import io.github.green4j.newa.rest.RestApiHelpFactory;
import io.github.green4j.newa.rest.RestApiParameters;

import static io.github.green4j.newa.rest.handles.Util.objectMemberNotNullable;

public class JsonHelp extends LazyStaticJsonRestHandler {
    public static RestApiHelpFactory factory() {
        return JsonHelp::new;
    }

    private final RestApiParameters restApiParameters;

    JsonHelp(final RestApiParameters builder) {
        this.restApiParameters = builder;
    }

    @Override
    protected void doHandle(final JsonGenerator output) {
        output.startObject();

        objectMemberNotNullable(output, "name", restApiParameters.name());
        objectMemberNotNullable(output, "description", restApiParameters.description());
        objectMemberNotNullable(output, "version", restApiParameters.fullVersion());
        objectMemberNotNullable(output, "buildVersion", restApiParameters.buildVersion());

        final int totalSize = restApiParameters.endpoints().length;

        if (totalSize > 0) {
            output.objectMember("methods");
            output.startArray();

            for (final Method method : restApiParameters.methods()) {
                final Endpoint[] endpoints = method.endpoints();

                if (endpoints.length == 0) {
                    continue;
                }

                output.startObject();
                output.objectMember("method");
                output.stringValue(method.name());
                output.objectMember("paths");
                output.startArray();

                for (final Endpoint ep : endpoints) {
                    output.startObject();
                    output.objectMember("path");
                    output.stringValue(ep.pathExpression(), true);
                    if (ep.description() != null) {
                        output.objectMember("description");
                        output.stringValue(ep.description(), true);
                    }
                    final String[] pathParamDescriptions = ep.pathParameterDescriptions();
                    if (pathParamDescriptions.length > 0) {
                        output.objectMember("pathParameters");
                        output.startArray();
                        for (final String pd : pathParamDescriptions) {
                            output.stringValue(pd, true);
                        }
                        output.endArray();
                    }
                    final String[] queryParamDescriptions = ep.queryParameterDescriptions();
                    if (queryParamDescriptions.length > 0) {
                        output.objectMember("queryParameters");
                        output.startArray();
                        for (final String pd : queryParamDescriptions) {
                            output.stringValue(pd, true);
                        }
                        output.endArray();
                    }
                    output.endObject();
                }
                output.endArray();
                output.endObject();
            }
            output.endArray();
        }
        output.endObject();
    }
}
