package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;

import java.util.List;

public class Json_Help extends LazyStaticJsonRestHandler {
    public static RestApi.HelpFactory factory() {
        return forBuilder -> new Json_Help(forBuilder);
    }

    private final RestApi.Builder builder;

    Json_Help(final RestApi.Builder builder) {
        this.builder = builder;
    }

    @Override
    protected void doHandle(final JsonGenerator output) {
        final String componentName = builder.componentName();

        final boolean hasMetaInfo = componentName != null && !componentName.isBlank();

        output.startObject();
        if (hasMetaInfo) {
            output.objectMember("component");
            output.stringValue(componentName, true);
            output.objectMember("componentBuildVersion");
            output.stringValue(builder.componentBuildVersion(), true);

            output.objectMember("api");
            output.stringValue(builder.apiName(), true);
            output.objectMember("apiVersion");
            output.stringValue(builder.apiVersion(), true);
        }
        final int totalSize = builder.endpoints().size();

        if (totalSize > 0) {
            output.objectMember("methods");
            output.startArray();

            for (final RestApi.Builder.Method method : builder.methods()) {
                final List<RestApi.Endpoint> endpoints = method.endpoints();

                if (endpoints.isEmpty()) {
                    continue;
                }

                output.startObject();
                output.objectMember("method");
                output.stringValue(method.name());
                output.objectMember("paths");
                output.startArray();

                for (final RestApi.Endpoint e : endpoints) {
                    output.startObject();
                    output.objectMember("path");
                    output.stringValue(e.pathExpression(), true);
                    if (e.description() != null) {
                        output.objectMember("description");
                        output.stringValue(e.description(), true);
                    }
                    final String[] pathParamDescriptions = e.pathParameterDescriptions();
                    if (pathParamDescriptions.length > 0) {
                        output.objectMember("pathParameters");
                        output.startArray();
                        for (final String pd : pathParamDescriptions) {
                            output.stringValue(pd, true);
                        }
                        output.endArray();
                    }
                    final String[] queryParamDescriptions = e.queryParameterDescriptions();
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