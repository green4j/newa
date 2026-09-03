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

package io.github.green4j.newa.example.rest.pair;

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
 * Two REST servers on two ports, run by one {@link Life}: a public api on every interface, and an admin api
 * - the jvm endpoints and {@code /shutdown} - reachable from the loopback only. That is what a second port
 * buys, and the only reason to pay for one: a different interface, a different pool of workers, or a
 * different limit on what a request may be.
 * <p>
 * {@link Life#all} is the whole of running a pair. It opens them in the order given, closes both when the
 * end is asked for, and - the part worth having a method for - closes what it already opened if a later one
 * fails to open, which no caller could do for itself: until the opener returns, the {@link Life} owns
 * nothing, so a server bound beside one which then failed is a server nothing would ever close.
 * <p>
 * Two things are still this example's own, because they are not a {@link Life}'s business:
 * <ul>
 *   <li><b>A server which dies alone has to say so.</b> A {@link Life} waits for {@code end(...)} and for
 *       nothing else, so each server ends the pair from its own {@code closeFuture} - or the process would
 *       stay up serving half of what it promises.</li>
 *   <li><b>Threads do not divide themselves.</b> Every {@code start()} makes event loop groups of its own
 *       and {@code workerThreads} defaults to a worker per core, which on two servers is two per core, all
 *       of them competing. So both are told what they get.</li>
 * </ul>
 * With one port to serve, none of this applies: {@code new Life().run(() -> RestServer.start(port, api))}
 * is the whole of it - see {@code rest.hello.HelloRestServer}.
 */
public class PairedRestServers {
    public static final String PUBLIC_API_NAME = "Public API";
    public static final String PUBLIC_API_DESCRIPTION = "What the world may ask for";
    public static final String ADMIN_API_NAME = "Admin API";
    public static final String ADMIN_API_DESCRIPTION = "What only this machine may ask for";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PUBLIC_PORT = 9009;
    public static final int ADMIN_PORT = 9010;

    public static final String PUBLIC_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PUBLIC_PORT);
    public static final String ADMIN_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, ADMIN_PORT);

    public static void main(final String[] args) throws Exception {
        // named for its role rather than its type: "end.end(...)" below would read badly
        final Life life = new Life();

        final RestApi adminApi = adminApi(life);
        final RestApi publicApi = publicApi();

        life.run(
                Life.all(
                        () -> start(adminApi, life, new NettyServerBuilder()
                                .port(ADMIN_PORT)
                                .host(LOCAL_IFC)      // the whole of the isolation: no other interface
                                .workerThreads(1)),   // it answers one operator, not the world
                        () -> start(publicApi, life, new NettyServerBuilder()
                                .port(PUBLIC_PORT)
                                .workerThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1)))
                ),
                new Life.Observer() {
                    @Override
                    public void onRunning() {
                        // both are open and this thread is about to park, which is the moment to say so
                        printUsage(publicApi, adminApi);
                    }

                    @Override
                    public void onEnding(final String cause) {
                        System.out.println("Ending: " + cause);
                    }
                }
        );

        System.out.println("Servers stopped");
    }

    /**
     * One server, and the one thing a {@link Life} cannot know about it: that a channel closed on its own
     * is the end of the pair. {@code end(...)} does no I/O - it releases {@code run} and returns - so the
     * closing still happens on the thread which called {@code run} rather than on the loop which is dying.
     *
     * @param api       to serve.
     * @param life  to end when this server's channel closes.
     * @param bootstrap of the transport, the threads and the port.
     * @return the server, running.
     * @throws InterruptedException if interrupted while binding.
     */
    private static NettyServer start(final RestApi api,
                                     final Life life,
                                     final NettyServerBuilder bootstrap) throws InterruptedException {
        final NettyServer server = RestServer.of(api).start(bootstrap);

        server.channel().closeFuture().addListener(
                closed -> life.end("Port " + server.port() + " closed"));

        return server;
    }

    private static RestApi publicApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                PUBLIC_API_NAME,
                PUBLIC_API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        builder.getJson("/hello/{name}",
                (context,
                 output) ->
                        output.stringValue(
                                String.format(
                                        "Hello %s!",
                                        context.pathParameters().valueRequired("name")
                                )
                        )
        ).withPathParameterDescriptions("name - Your name");

        builder.root().getJson("/version",
                (context,
                 output) -> output.stringValue(builder.fullVersion()));

        return builder.buildWithHelp(JsonHelp.factory());
    }

    private static RestApi adminApi(final Life shutdown) {
        final RestApiBuilder builder = new RestApiBuilder(
                ADMIN_API_NAME,
                ADMIN_API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        builder.getJson("/jvm/info", new JsonJvmInfo());
        builder.getJson("/jvm/threads", new JsonJvmThreadDump());

        // one Ender for the pair: this ends both servers, and it is registered before either exists -
        // which is why a Life is an Ender from the moment it is constructed
        builder.postJson("/shutdown", new JsonExecute(() -> shutdown.end("Called by admin API")));

        return builder.buildWithHelp(JsonHelp.factory());
    }

    private static void printUsage(final RestApi publicApi,
                                   final RestApi adminApi) {
        System.out.printf("Public api on %s, admin api on %s (loopback only). Try:%n",
                PUBLIC_ADDRESS, ADMIN_ADDRESS);
        System.out.printf("  curl -s %s/v1/hello/world%n", PUBLIC_ADDRESS);
        System.out.printf("  curl -s %s/version           -> published on the root, without /v1%n",
                PUBLIC_ADDRESS);
        System.out.printf("  curl -s %s%s%n", PUBLIC_ADDRESS, publicApi.helpPath());
        System.out.printf("  curl -s %s/v1/jvm/info       -> the admin port, and not on the public one%n",
                ADMIN_ADDRESS);
        System.out.printf("  curl -s %s%s%n", ADMIN_ADDRESS, adminApi.helpPath());
        System.out.printf("  curl -sX POST %s/v1/shutdown   -> stops both%n", ADMIN_ADDRESS);
    }
}
