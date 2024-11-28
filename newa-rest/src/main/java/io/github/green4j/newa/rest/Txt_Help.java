package io.github.green4j.newa.rest;

import io.github.green4j.newa.text.LineAppendable;

import java.util.List;

public class Txt_Help extends LazyStaticTxtRestHandler {
    public static RestApi.HelpFactory factory() {
        return forBuilder -> new Txt_Help(forBuilder);
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
                final List<RestApi.Endpoint> endpoints = method.endpoints();

                if (endpoints.isEmpty()) {
                    continue;
                }

                if (hasMetaInfo) {
                    output.append("    ");
                }
                output.appendln(method.name());

                for (int i = 0; i < endpoints.size(); i++) {
                    final RestApi.Endpoint e = endpoints.get(i);
                    if (i > 0) {
                        output.appendln();
                    }

                    if (hasMetaInfo) {
                        output.append("        ");
                    } else {
                        output.append("    ");
                    }
                    output.appendln(e.pathExpression());
                    if (e.description() != null) {
                        if (hasMetaInfo) {
                            output.append("            ");
                        } else {
                            output.append("        ");
                        }
                        output.appendln(e.description());
                    }

                    final String[] pathParamDescriptions = e.pathParameterDescriptions();
                    if (pathParamDescriptions.length > 0) {
                        output.append("            ");
                        output.appendln("path parameters: ");
                        for (int p = 0; p < pathParamDescriptions.length; p++) {
                            output.append("                ");
                            output.appendln(pathParamDescriptions[p]);
                        }
                    }

                    final String[] queryParamDescriptions = e.queryParameterDescriptions();
                    if (queryParamDescriptions.length > 0) {
                        output.append("            ");
                        output.appendln("query parameters: ");
                        for (int p = 0; p < queryParamDescriptions.length; p++) {
                            output.append("                ");
                            output.appendln(queryParamDescriptions[p]);
                        }
                    }
                }
            }
        }
    }
}
