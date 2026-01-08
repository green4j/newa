package io.github.green4j.newa.rest.handles;

import io.github.green4j.newa.rest.Endpoint;
import io.github.green4j.newa.rest.LazyStaticTxtRestHandler;
import io.github.green4j.newa.rest.Method;
import io.github.green4j.newa.rest.RestApiHelpFactory;
import io.github.green4j.newa.rest.RestApiParameters;
import io.github.green4j.newa.text.LineAppendable;

import static io.github.green4j.newa.rest.handles.Util.appendlnNotNullable;

public class Txt_Help extends LazyStaticTxtRestHandler {
    public static RestApiHelpFactory factory() {
        return Txt_Help::new;
    }

    private final RestApiParameters restApiParameters;

    Txt_Help(final RestApiParameters restApiParameters) {
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
