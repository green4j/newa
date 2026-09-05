/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.ws.broadcast;

import io.github.green4j.newa.example.ws.StdOutWsApiObserver;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;

import java.util.concurrent.TimeUnit;

public class BroadcastWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();

        final WsApiBuilder apiBuilder = new WsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withPingIntervalMs(10_000) // shorter than the 30s/90s default, so that the
                .withReadTimeoutMs(30_000)  // keep-alive is something you can watch happen here
                .withObservers(StdOutWsApiObserver.factory());

        final WsApi api = apiBuilder.build();

        life.run(() -> {
            final NettyServer server = WsServer.of(api)
                    .withCompression()
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started and listening on %s%s. Try:%n",
                    LOCAL_SERVER_ADDRESS, api.websocketPath());
            System.out.printf("  wscat -c %s%s   -> a line every 5 seconds, to every session at once%n",
                    LOCAL_SERVER_ADDRESS, api.websocketPath());
            System.out.println("  open a second one: both get the same broadcast, off one timer");

            // on the loops the sessions already live on, so a broadcast reaches them without a hand-off
            server.workerGroup().scheduleWithFixedDelay(
                    () -> api.broadcastText("Hello from WS server"),
                    5_000,
                    5_000,
                    TimeUnit.MILLISECONDS
            );

            return server;
        });

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
