package io.github.green4j.newa.rest.handles;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.rest.Endpoint;
import io.github.green4j.newa.rest.LazyStaticJsonRestHandler;
import io.github.green4j.newa.rest.Method;
import io.github.green4j.newa.rest.RestApiHelpFactory;
import io.github.green4j.newa.rest.RestApiParameters;

import static io.github.green4j.newa.rest.handles.Util.objectMemberNotNullable;

public class Json_Help extends LazyStaticJsonRestHandler {
    public static RestApiHelpFactory factory() {
        return Json_Help::new;
    }

    private final RestApiParameters restApiParameters;

    Json_Help(final RestApiParameters builder) {
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
