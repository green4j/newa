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

package io.github.green4j.newa.example.ws.broadcast;

import io.github.green4j.newa.example.ws.StdOutWsApiObserverFactory;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.SimpleWsApiBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsServer;

import java.util.concurrent.TimeUnit;

public class BroadcastWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();

        final SimpleWsApiBuilder apiBuilder = new SimpleWsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withPingIntervalMs(10_000) // shorter than the 30s/90s default, so that the
                .withReadTimeoutMs(30_000)  // keep-alive is something you can watch happen here
                .withObservers(new StdOutWsApiObserverFactory());

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
                    () -> api.broadcast("Hello from WS server"),
                    5_000,
                    5_000,
                    TimeUnit.MILLISECONDS
            );

            return server;
        });

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
