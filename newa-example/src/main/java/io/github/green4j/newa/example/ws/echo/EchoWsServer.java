/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.ws.echo;

import io.github.green4j.newa.example.ws.StdOutWsApiObserver;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;


public class EchoWsServer {
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
                .withTextReceiver((session, message, last) -> {
                    System.out.printf(
                            "Received: '%s' from: %s%n",
                            message.toString(),
                            session.toString()
                    );
                    session.sendText(message);
                })
                .withBinaryReceiver((session, payload, last) -> {
                    System.out.printf(
                            "Received: %d bytes%s from: %s%n",
                            payload.readableBytes(),
                            last ? "" : " (to be continued)",
                            session.toString()
                    );
                    session.sendBinary(payload.retain()); // the buffer is the decoder's, and it is released
                    // the moment this returns, so what is sent on has to be retained. A message which
                    // arrived in pieces goes back as one frame per piece
                })
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
            System.out.printf("  wscat -c %s%s   -> then type anything: it comes back%n",
                    LOCAL_SERVER_ADDRESS, api.websocketPath());
            System.out.println("  and wait: the server pings every 10s and drops a session silent for 30s");

            return server;
        });

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
