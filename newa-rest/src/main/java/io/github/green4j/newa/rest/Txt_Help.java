package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;

import java.util.List;

public class Txt_Help extends LazyStaticTxtRestHandler {
    public static RestApi.HelpFactory factory() {
        return Txt_Help::new;
    }

    private final RestApi.Builder builder;

    Txt_Help(final RestApi.Builder builder) {
        this.builder = builder;
    }

    @Override
    protected void doHandle(final LineAppendable output) {
        final String componentName = builder.componentName();

        final boolean hasMetaInfo = componentName != null && !componentName.isBlank();

        if (hasMetaInfo) {
            output.append("component: ");
            output.appendln(componentName);
            output.append("component build version: ");
            output.appendln(builder.componentBuildVersion());

            output.append("api: ");
            output.appendln(builder.apiName());
            output.append("api version: ");
            output.appendln(builder.apiVersion());
        }

        final int totalSize = builder.endpoints().size();

        if (totalSize > 0) {
            for (final RestApi.Builder.Method method : builder.methods()) {
                final List<Endpoint> endpoints = method.endpoints();

                if (endpoints.isEmpty()) {
                    continue;
                }

                if (hasMetaInfo) {
                    output.tab(1);
                }
                output.appendln(method.name());

                for (int i = 0; i < endpoints.size(); i++) {
                    final Endpoint ep = endpoints.get(i);
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
