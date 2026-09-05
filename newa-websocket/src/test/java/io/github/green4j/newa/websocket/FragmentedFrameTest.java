/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.NettyServer;
import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a message which arrives in several frames looks like to a {@link Receiver}: one call per frame, with
 * {@code last} saying which of them ends the message, and the frames of one message going to the receiver of
 * the frame which began it - a continuation of a binary message is binary however the text before it was
 * handled.
 * <p>
 * Nothing here assembles a message: it is handed over piece by piece, because a piece is what the frame
 * limit of the pipeline bounds and holding the pieces would bound nothing. The one thing which is put back
 * together is a character cut in two by a frame boundary, which nothing above this could repair.
 */
class FragmentedFrameTest {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/ws/v1";

    private static final class Recorder implements Receiver.Text, Receiver.Binary {
        private final List<String> pieces = Collections.synchronizedList(new ArrayList<>());
        private volatile ByteBuf kept;

        @Override
        public void text(final ClientSession session,
                         final CharSequence message,
                         final boolean last) {
            pieces.add("text:" + message + ':' + last);
        }

        @Override
        public void binary(final ClientSession session,
                           final ByteBuf payload,
                           final boolean last) {
            pieces.add("binary:" + payload.toString(CharsetUtil.UTF_8) + ':' + last);
            if (kept == null) {
                kept = payload.retain(); // the buffer is the decoder's, and this is what keeping it means
            }
        }
    }

    private NettyServer server;
    private Recorder recorder;

    private RawWebSocket connect() throws Exception {
        recorder = new Recorder();
        server = WsServer.start(0, new WsApiBuilder(1)
                .withPathPrefix("ws")
                .withTextReceiver(recorder)
                .withBinaryReceiver(recorder)
                .withPingIntervalMs(0) // nothing but what the test sends is on this connection
                .withReadTimeoutMs(0)
                .build());

        final RawWebSocket client = new RawWebSocket(HOST, server.port());
        client.handshake(PATH);
        return client;
    }

    private List<String> awaitPieces(final int count) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (recorder.pieces.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        synchronized (recorder.pieces) {
            return new ArrayList<>(recorder.pieces);
        }
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (recorder != null && recorder.kept != null && recorder.kept.refCnt() > 0) {
            recorder.kept.release();
        }
    }

    @Test
    public void aWholeMessageArrivesAsOneCallWithLastSet() throws Exception {
        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.BINARY, true, bytes("hello"));
            client.sendText(bytes("world"));

            Assertions.assertEquals(List.of("binary:hello:true", "text:world:true"), awaitPieces(2));
        }
    }

    @Test
    public void aFragmentedBinaryMessageArrivesPieceByPiece() throws Exception {
        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.BINARY, false, bytes("ab"));
            client.sendFrame(RawWebSocket.CONTINUATION, false, bytes("cd"));
            client.sendFrame(RawWebSocket.CONTINUATION, true, bytes("ef"));

            Assertions.assertEquals(
                    List.of("binary:ab:false", "binary:cd:false", "binary:ef:true"),
                    awaitPieces(3));
        }
    }

    @Test
    public void aFragmentedTextMessageArrivesPieceByPiece() throws Exception {
        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.TEXT, false, bytes("ab"));
            client.sendFrame(RawWebSocket.CONTINUATION, false, bytes("cd"));
            client.sendFrame(RawWebSocket.CONTINUATION, true, bytes("ef"));

            Assertions.assertEquals(
                    List.of("text:ab:false", "text:cd:false", "text:ef:true"),
                    awaitPieces(3));
        }
    }

    /**
     * A frame boundary is drawn in bytes and a character may lie across it. Decoding such a fragment on its
     * own would put a replacement character on the seam - and there would be no putting it back, since what
     * the receiver is handed by then is text.
     */
    @Test
    public void aCharacterCutInTwoIsPutBackTogether() throws Exception {
        final byte[] character = bytes("\u00e9"); // two bytes, and the frames below cut it in half
        Assertions.assertEquals(2, character.length);

        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.TEXT, false, new byte[] {'a', character[0]});
            client.sendFrame(RawWebSocket.CONTINUATION, true, new byte[] {character[1], 'b'});

            Assertions.assertEquals(
                    List.of("text:a:false", "text:\u00e9b:true"),
                    awaitPieces(2));
        }
    }

    /**
     * A fragment which carries nothing but a piece of a character says nothing yet, so nothing is handed
     * over for it - a call with an empty message would read as an empty message rather than as a wait.
     */
    @Test
    public void aFragmentWhollyInsideOneCharacterIsHeldBack() throws Exception {
        final byte[] character = bytes("\u20ac"); // three bytes, one per frame
        Assertions.assertEquals(3, character.length);

        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.TEXT, false, new byte[] {character[0]});
            client.sendFrame(RawWebSocket.CONTINUATION, false, new byte[] {character[1]});
            client.sendFrame(RawWebSocket.CONTINUATION, true, new byte[] {character[2]});

            Assertions.assertEquals(List.of("text:\u20ac:true"), awaitPieces(1));
        }
    }

    /**
     * What is handed over is the decoder's, and the decoder releases it the moment the call returns. A
     * receiver which keeps it retains it, and that reference is the one which is still readable afterwards.
     */
    @Test
    public void theBufferIsTheDecodersUntilTheReceiverRetainsIt() throws Exception {
        try (RawWebSocket client = connect()) {
            client.sendFrame(RawWebSocket.BINARY, true, bytes("kept"));

            awaitPieces(1);

            final ByteBuf kept = recorder.kept;
            Assertions.assertNotNull(kept);

            final long deadline = System.currentTimeMillis() + 5_000;
            while (kept.refCnt() > 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5); // the decoder releases its own reference once the call has returned
            }

            Assertions.assertEquals(1, kept.refCnt(), "what is left is the reference the receiver took");
            Assertions.assertEquals("kept", kept.toString(CharsetUtil.UTF_8));
        }
    }
}
