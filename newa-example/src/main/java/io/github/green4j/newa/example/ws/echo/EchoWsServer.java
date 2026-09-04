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

package io.github.green4j.newa.example.ws.echo;

import io.github.green4j.newa.example.ws.StdOutWsApiObserver;
import io.netty.buffer.ByteBuf;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.Receiver;
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

        final Receiver receiver = new Receiver() {
            @Override
            public void text(final ClientSession session,
                             final CharSequence message,
                             final boolean last) {
                System.out.printf(
                        "Received: '%s' from: %s%n",
                        message.toString(),
                        session.toString()
                );
                session.sendText(message);
            }

            @Override
            public void binary(final ClientSession session,
                               final ByteBuf payload,
                               final boolean last) {
                System.out.printf(
                        "Received: %d bytes%s from: %s%n",
                        payload.readableBytes(),
                        last ? "" : " (to be continued)",
                        session.toString()
                );
                session.sendBinary(payload.retain()); // the buffer is the decoder's, and it is released
                // the moment this returns, so what is sent on has to be retained. A message which arrived
                // in pieces goes back as one frame per piece
            }
        };

        final WsApiBuilder apiBuilder = new WsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withReceiver(receiver)
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
