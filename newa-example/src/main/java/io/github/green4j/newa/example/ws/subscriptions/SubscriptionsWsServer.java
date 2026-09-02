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

package io.github.green4j.newa.example.ws.subscriptions;

import io.github.green4j.newa.example.ws.StdOutWsApiObserverFactory;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsServer;
import io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder;


public class SubscriptionsWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();

        final Channels channels = new Channels();

        final SubscriptionWsApiBuilder apiBuilder = new SubscriptionWsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withReceiver(channels)
                .withPingIntervalMs(10_000)
                .withObservers(new StdOutWsApiObserverFactory())
                .withSkipOnBackPressure(); // both channels here restore a session with
        // a snapshot, so one which can not keep up is re-synchronized, not disconnected

        final WsApi api = apiBuilder.build();

        life.run(() -> {
            final NettyServer server = WsServer.of(api)
                    .withCompression()
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf(
                    "Server started and listening on %s. Websocket path: %s%s%n",
                    LOCAL_SERVER_ADDRESS,
                    LOCAL_SERVER_ADDRESS,
                    api.websocketPath()
            );

            return server;
        });

        channels.close();

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
