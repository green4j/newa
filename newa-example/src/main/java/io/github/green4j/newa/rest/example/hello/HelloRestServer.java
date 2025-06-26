package io.github.green4j.newa.rest.example.hello;

import io.github.green4j.newa.rest.AbstractRestServerStarter;
import io.github.green4j.newa.rest.Json_Help;
import io.github.green4j.newa.rest.Json_JvmInfo;
import io.github.green4j.newa.rest.Json_JvmThreadDump;
import io.github.green4j.newa.rest.Json_Shutdown;
import io.github.green4j.newa.rest.RestApi;

public class HelloRestServer extends AbstractRestServerStarter {
    private final StringBuilder helloMessage = new StringBuilder();
    private final StringBuilder byeMessage = new StringBuilder();

    public HelloRestServer() {
        super("Hello API",
                1,
                "My Hello API Server",
                "0.0.1",
                "127.0.0.1",
                9009);
    }

    @Override
    protected RestApi buildRestApi(final RestApi.Builder builder) {
        builder.getJson("/hello/{name}", (request, pathParameters, output) -> {
            output.stringValue(String.format("Hello %s!", pathParameters.parameterValue("name")));
        }).withPathParameterDescriptions("name - Your name");
        builder.getJson("/jvm/info", new Json_JvmInfo());
        builder.getJson("/jvm/threads", new Json_JvmThreadDump());
        builder.getJson("/shutdown", new Json_Shutdown(aSwitch()));

        // API version to publish without path's prefix,
        // directly on the root
        builder.root().getJson("/version", (request, pathParameters, output) -> {
            output.stringValue(builder.apiVersion());
        });

        final RestApi result = builder.buildWithHelp(Json_Help.factory());

        final String serverAddress = String.format("http://%s:%d", localIfc, port);

        helloMessage.append(componentName).append(" started and listening to: ").append(serverAddress).append("... ")
                .append("Help is available on: ").append(serverAddress).append(result.helpPath());

        byeMessage.append(componentName).append(" stopped");

        return result;
    }

    @Override
    protected void onServerStarted() {
        System.out.println(helloMessage);
    }

    @Override
    protected void onServerStopped() {
        System.out.println(byeMessage);
    }

    public static void main(final String[] args) {
        new HelloRestServer().start();
    }
}
