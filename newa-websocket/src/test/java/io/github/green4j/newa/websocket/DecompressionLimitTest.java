/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * A limit on the frame as it arrives would bound nothing under permessage-deflate: a frame small enough to
 * be accepted inflates to whatever it was built to inflate to. So
 * {@link WsServer#withMaxFramePayloadLength(int)} is given to the compression handler as well, and these
 * drive that with a real deflated frame rather than by reading the number back off the pipeline.
 */
class DecompressionLimitTest {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws/v1";
    private static final String EXTENSION = "Sec-WebSocket-Extensions: permessage-deflate";

    private static final int TEXT = 0x1;
    private static final int CONTINUATION = 0x0;

    /**
     * Large enough to pass any cap this server would have, small enough to deflate to a few hundred bytes
     * and therefore to arrive well inside the wire limit.
     */
    private static final int BOMB_LENGTH = 4 * 1024 * 1024;

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static WsApi echoApi() {
        return new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(Receivers.echo())
                .build();
    }

    /**
     * @param length of the run of one byte to deflate.
     * @return it as a permessage-deflate payload: raw deflate with the empty block the extension strips
     *         taken off the end.
     */
    private static byte[] deflated(final int length) {
        final byte[] source = new byte[length];
        Arrays.fill(source, (byte) 'a');

        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(source);
            deflater.finish();

            final byte[] out = new byte[length];
            final int written = deflater.deflate(out);
            Assertions.assertTrue(deflater.finished(), "The whole bomb should have deflated at once");
            Assertions.assertTrue(written > 4, "Nothing was deflated");
            return Arrays.copyOf(out, written - 4); // the trailing 00 00 FF FF is not sent
        } finally {
            deflater.end();
        }
    }

    /**
     * @param lengths of the runs of one byte to deflate, one per fragment of the message.
     * @return one permessage-deflate payload per fragment: each is its own sync-flushed block, so each
     *         inflates to exactly the length asked for, and the last one has the empty block the extension
     *         strips taken off the end.
     */
    private static byte[][] deflatedFragments(final int... lengths) {
        final byte[][] fragments = new byte[lengths.length][];

        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            for (int i = 0; i < lengths.length; i++) {
                final byte[] source = new byte[lengths[i]];
                Arrays.fill(source, (byte) 'a');

                deflater.setInput(source);
                final byte[] out = new byte[lengths[i] + 64];
                final int written = deflater.deflate(out, 0, out.length, Deflater.SYNC_FLUSH);
                Assertions.assertTrue(deflater.needsInput(), "The fragment did not deflate at once");

                final boolean last = i == lengths.length - 1;
                fragments[i] = Arrays.copyOf(out, last ? written - 4 : written);
            }
        } finally {
            deflater.end();
        }

        return fragments;
    }

    private static void assertNegotiated(final String head) {
        Assertions.assertTrue(
                head.toLowerCase(java.util.Locale.ROOT).contains("permessage-deflate"),
                "The server did not negotiate compression: " + head
        );
    }

    @Test
    public void aBombIsRefusedByDefault() throws Exception {
        // no cap given and no memory budget: the default is what stands between this server and the heap
        server = WsServer.of(echoApi())
                .withCompression()
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            assertNegotiated(client.handshake(PATH, EXTENSION));
            client.sendFrame(TEXT, true, true, deflated(BOMB_LENGTH));

            Assertions.assertTrue(client.awaitClose(), "The connection was left open");
        }
    }

    @Test
    public void theCapIsWhatTheServerWasGiven() throws Exception {
        server = WsServer.of(echoApi())
                .withCompression()
                .withMaxFramePayloadLength(64)
                .start(0);

        final byte[] payload = deflated(1024);
        Assertions.assertTrue(payload.length <= 64, "The wire limit would refuse this frame by itself");

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            assertNegotiated(client.handshake(PATH, EXTENSION));
            client.sendFrame(TEXT, true, true, payload);

            Assertions.assertTrue(client.awaitClose(), "The connection was left open");
        }
    }

    @Test
    public void aFrameWithinTheCapIsStillAnswered() throws Exception {
        server = WsServer.of(echoApi())
                .withCompression()
                .withMaxFramePayloadLength(8 * 1024)
                .start(0);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            assertNegotiated(client.handshake(PATH, EXTENSION));
            client.sendFrame(TEXT, true, true, deflated(1024));

            Assertions.assertEquals(TEXT, client.readFrame()[0]);
        }
    }

    /**
     * The inflater lives for the whole message, so the cap has to hold for a continuation frame as well as
     * for the one which began it: the first fragment here is inside the cap and the second is not.
     */
    @Test
    public void aContinuationFrameIsCappedLikeTheFrameWhichBeganTheMessage() throws Exception {
        final int cap = 64 * 1024;

        server = WsServer.of(echoApi())
                .withCompression()
                .withMaxFramePayloadLength(cap)
                .start(0);

        final byte[][] fragments = deflatedFragments(cap / 2, 2 * cap);

        try (RawWebSocket client = new RawWebSocket(HOST, server.port())) {
            assertNegotiated(client.handshake(PATH, EXTENSION));

            for (int i = 0; i < fragments.length; i++) {
                Assertions.assertTrue(
                        fragments[i].length <= cap,
                        "The frame is past the cap on the wire, before anything inflates it"
                );
                client.sendFrame(
                        i == 0 ? TEXT : CONTINUATION,
                        i == fragments.length - 1,
                        i == 0, // permessage-deflate marks the frame which begins the message and no other
                        fragments[i]
                );
            }

            Assertions.assertTrue(client.awaitClose(), "The connection was left open");
        }
    }

    @Test
    public void aCapWhichBoundsNothingIsRefused() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WsServer.of(echoApi()).withMaxFramePayloadLength(0)
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WsServer.of(echoApi()).withMaxFramePayloadLength(-1)
        );
    }
}
