package io.github.green4j.newa.example.rest.hello;

import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.handles.Json_Execute;
import io.github.green4j.newa.rest.handles.Json_Help;
import io.github.green4j.newa.rest.handles.Json_JvmInfo;
import io.github.green4j.newa.rest.handles.Json_JvmThreadDump;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;

/**
 * The api is the whole of this example: {@link RestServer} assembles the pipeline and
 * {@link NettyServerBuilder} the bootstrap under it, so the only lines here which are not routes are the
 * ones that start it.
 * <p>
 * See {@code rest.pipeline.PipelineRestServer} for the same server with both written out by hand, which is
 * what you want as soon as the pipeline itself needs changing.
 */
public class HelloRestServer {
    public static final String API_NAME = "Hello API";
    public static final String API_DESCRIPTION = "My Hello API Server";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9009;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        // named for its role rather than its type: "end.end(...)" below would read badly
        final Life shutdown = new Life();

        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        apiBuilder.getJson("/hello/{name}",
                (context,
                 output) ->
                        output.stringValue(
                                String.format(
                                        "Hello %s!",
                                        context.pathParameters().valueRequired("name")
                                )
                        )
        ).withPathParameterDescriptions("name - Your name");
        apiBuilder.getJson("/jvm/info", new Json_JvmInfo());
        apiBuilder.getJson("/jvm/threads", new Json_JvmThreadDump());
        apiBuilder.postJson(
                "/shutdown",
                new Json_Execute(
                        () -> shutdown.end("Called by REST API")
                )
        );

        // API version to publish without path's prefix,
        // directly on the root
        apiBuilder
                .root()
                .getJson(
                        "/version",
                        (context,
                         output) ->
                                output.stringValue(apiBuilder.fullVersion()));

        final RestApi api = apiBuilder.buildWithHelp(Json_Help.factory());

        shutdown.run(() -> {
            final NettyServer server = RestServer.of(api)
                    .withCompression()
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf(
                    "Server started and listening on %s. Help is available on %s%s%n",
                    LOCAL_SERVER_ADDRESS,
                    LOCAL_SERVER_ADDRESS,
                    api.helpPath()
            );

            // End owns the lifecycle: it parks this thread until the end is asked for, adds the JVM shutdown
            // hook, and closes the server here rather than on the event loop the /shutdown endpoint runs on

            return server;
        });

        System.out.println("Server stopped");
    }
}
