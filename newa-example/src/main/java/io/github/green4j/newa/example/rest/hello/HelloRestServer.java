/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.rest.hello;

import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.handles.JsonExecute;
import io.github.green4j.newa.rest.handles.JsonHelp;
import io.github.green4j.newa.rest.handles.JsonJvmInfo;
import io.github.green4j.newa.rest.handles.JsonJvmThreadDump;
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
        final Life life = new Life();

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
        apiBuilder.getJson("/jvm/info", new JsonJvmInfo());
        apiBuilder.getJson("/jvm/threads", new JsonJvmThreadDump());
        apiBuilder.postJson(
                "/shutdown",
                new JsonExecute(
                        () -> life.end("Called by REST API")
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

        final RestApi api = apiBuilder.buildWithHelp(JsonHelp.factory());

        life.run(() -> {
            final NettyServer server = RestServer.of(api)
                    .withCompression()
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started and listening on %s. Try:%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/hello/world%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/version           -> published on the root, without /v1%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/jvm/info%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/jvm/threads%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s%s   -> the api describing itself%n",
                    LOCAL_SERVER_ADDRESS, api.helpPath());
            System.out.printf("  curl -sX POST %s/v1/shutdown   -> stops this server%n",
                    LOCAL_SERVER_ADDRESS);

            // the Life owns the lifecycle: it parks this thread until the end is asked for, adds the JVM
            // shutdown hook, and closes the server here rather than on the event loop /shutdown runs on

            return server;
        });

        System.out.println("Server stopped");
    }
}
