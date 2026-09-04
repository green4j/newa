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

import java.nio.charset.StandardCharsets;

class DeadlineTest {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws/v1";

    private static final int DEADLINE_MS = 250;
    private static final long PAST_IT_MS = DEADLINE_MS * 4L;

    private static final int TEXT = 0x1;

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /**
     * @return an api whose sessions watch nothing themselves, so that whatever closes a connection in these
     *         tests can only be the deadline.
     */
    private static WsApi echoApiWithoutAKeepAlive() {
        return new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withPingIntervalMs(0)
                .withReadTimeoutMs(0)
                .withReceiver(Receivers.echo())
                .build();
    }

    @Test
    public void theDefaultIsTheSameNumberTheHttpServersUse() {
        Assertions.assertEquals(30_000, WsServer.DEFAULT_DEADLINE_MS);
    }

    @Test
    public void aConnectionWhichNeverHandshakesIsClosed() throws Exception {
        server = WsServer.of(echoApiWithoutAKeepAlive())
                .withRequestDeadlineMs(DEADLINE_MS)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            // it connects and says nothing at all - the cheapest thing a peer can do, and until now the
            // longest a socket could be held for
            Assertions.assertTrue(client.awaitClose(), "the connection was left open");
        }
    }

    @Test
    public void aHandshakeDribbledOutIsClosed() throws Exception {
        // the peer no idle timeout catches: it is sending all the while, a byte at a time
        server = WsServer.of(echoApiWithoutAKeepAlive())
                .withRequestDeadlineMs(DEADLINE_MS)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            final byte[] head = ("GET " + PATH + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n")
                    .getBytes(StandardCharsets.US_ASCII);

            Assertions.assertTrue(dribble(client, head), "a handshake dribbled out was let through");
        }
    }

    @Test
    public void aSessionWhichSaysNothingIsNotClosed() throws Exception {
        server = WsServer.of(echoApiWithoutAKeepAlive())
                .withRequestDeadlineMs(DEADLINE_MS)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);

            // nothing is read from this session and nothing is written to it, so nothing is on a clock: the
            // deadline judges what has begun arriving, and a session which is merely quiet has begun nothing
            Thread.sleep(PAST_IT_MS);

            client.sendText("still here".getBytes("UTF-8"));

            Assertions.assertEquals(TEXT, client.readFrame()[0], "the session was closed under it");
        }
    }

    @Test
    public void aFrameDribbledOutIsClosed() throws Exception {
        // the same rule, applied to what a session sends: after the handshake the message is a frame
        server = WsServer.of(echoApiWithoutAKeepAlive())
                .withRequestDeadlineMs(DEADLINE_MS)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            client.handshake(PATH);

            // FIN, text, masked, sixteen bytes promised - and then the mask and the payload a byte at a time
            final byte[] half = {(byte) 0x81, (byte) 0x90, 0x01, 0x02, 0x03, 0x04, 0x0a, 0x0b};

            Assertions.assertTrue(dribble(client, half), "a frame dribbled out was waited for");
        }
    }

    @Test
    public void zeroHoldsAConnectionWhichSaysNothing() throws Exception {
        server = WsServer.of(echoApiWithoutAKeepAlive())
                .withRequestDeadlineMs(0)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            Thread.sleep(PAST_IT_MS);

            // still there to be handshaken, which is what having no deadline means
            Assertions.assertEquals(
                    "HTTP/1.1 101 Switching Protocols", statusLineOf(client.handshake(PATH)));
        }
    }

    /**
     * Sends the bytes one at a time, a quarter of the deadline apart, until they run out or the connection
     * is closed under them.
     *
     * @param client to dribble into.
     * @param bytes to send.
     * @return whether the connection was closed before the bytes ran out.
     * @throws Exception if the socket does, which is a close by another name.
     */
    private static boolean dribble(final RawWebSocket client,
                                   final byte[] bytes) throws Exception {
        try {
            for (int i = 0; i < bytes.length * 4; i++) {
                client.write(new byte[]{bytes[i % bytes.length]});
                Thread.sleep(DEADLINE_MS / 4);
            }
        } catch (final java.io.IOException closedUnderIt) {
            return true; // the write is what noticed it, which is the same finding
        }
        return client.awaitClose();
    }

    private static String statusLineOf(final String head) {
        final int end = head.indexOf("\r\n");
        return end < 0 ? head : head.substring(0, end);
    }
}
