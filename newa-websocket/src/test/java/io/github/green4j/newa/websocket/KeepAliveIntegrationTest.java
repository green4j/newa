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

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The keep-alive pair over a real socket, which is the only place the pong can be tested: the server pings
 * a session which does nothing but listen, and it is the answer to that ping - a frame the api never sees
 * and reports to nobody - which is the difference between a client that is there and one that is not.
 */
class KeepAliveIntegrationTest {
    private static final int PING_INTERVAL_MS = 150;
    private static final int READ_TIMEOUT_MS = 500;

    private static final long PATIENCE_MILLIS = 5_000;

    private final AtomicBoolean sessionClosed = new AtomicBoolean();

    private NettyServer server;

    /**
     * A client which listens and says nothing else - the shape of every fan-out subscriber. It answers a
     * ping with a pong, and there is no talking it out of that: the JDK implementation replies whatever
     * the listener does, which is exactly what a conforming client is supposed to do. Silence is therefore
     * arranged by not pinging rather than by not answering.
     *
     * @return the connected client.
     * @throws Exception if the handshake does not complete.
     */
    private WebSocket connect() throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + server.port() + "/ws/v1"),
                        new WebSocket.Listener() { }
                )
                .get();
    }

    private void awaitClosed(final boolean expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + PATIENCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (sessionClosed.get()) {
                break;
            }
            Thread.sleep(20);
        }
        Assertions.assertEquals(expected, sessionClosed.get());
    }

    private void start(final int pingIntervalMs) throws InterruptedException {
        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix("ws")
                .withPingIntervalMs(pingIntervalMs)
                .withReadTimeoutMs(READ_TIMEOUT_MS)
                .withObservers(() -> new WsApiObserver() {
                    @Override
                    public void onSessionClosed(final long durationNanos) {
                        sessionClosed.set(true);
                    }
                })
                .build();

        server = WsServer.start(0, api);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void shouldKeepAClientWhichOnlyListensButAnswersThePing() throws Exception {
        start(PING_INTERVAL_MS);

        final WebSocket client = connect();
        try {
            Thread.sleep(READ_TIMEOUT_MS * 3L); // several timeouts' worth of saying nothing at all

            // The pong is the whole point: it is a frame the api never reports and nothing above the
            // protocol ever sees, and it is the only thing standing between this client and the timeout
            // which closes the one below.
            Assertions.assertFalse(sessionClosed.get(),
                    "a subscriber which never sends is not a subscriber which is gone");
        } finally {
            client.abort();
        }
    }

    @Test
    void shouldCloseAClientWhichSaysNothingAtAll() throws Exception {
        start(0); // nothing is pinged, so nothing has anything to answer, and this is what a peer whose
        // host vanished looks like from here

        final WebSocket client = connect();
        try {
            awaitClosed(true);
        } finally {
            client.abort();
        }
    }
}
