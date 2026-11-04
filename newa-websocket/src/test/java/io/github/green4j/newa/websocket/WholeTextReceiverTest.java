/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link WholeTextReceiver}: a message in pieces is handed on whole, a message in one frame is handed on as
 * it came, at no cost, and a message past the limit ends the session with a {@code 1009}.
 */
class WholeTextReceiverTest {
    private static final int MAX_MESSAGE_LENGTH = 8;

    private static final class Recorder implements Receiver.Text {
        private final List<String> messages = new ArrayList<>();
        private final List<CharSequence> handed = new ArrayList<>();

        @Override
        public void text(final ClientSession session,
                         final CharSequence message,
                         final boolean last) {
            messages.add(message + ":" + last);
            handed.add(message); // what it was handed, to see which object it is, not to read it again
        }
    }

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final Recorder recorder = new Recorder();
    private final ClientSession session = new ClientSessions(null, null).newSession(
            new ClientSessionContext(new NoWritingResult(), new WholeTextReceiver(MAX_MESSAGE_LENGTH, recorder),
                    null, channel, 0));

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void aMessageInOneFrameIsHandedOnAsItCame() {
        final String message = "whole";
        session.receive(message, true);

        Assertions.assertEquals(List.of("whole:true"), recorder.messages);
        Assertions.assertSame(message, recorder.handed.get(0), "A message in one frame was copied");
    }

    @Test
    void aMessageInPiecesIsHandedOnWhole() {
        session.receive("ab", false);
        session.receive("cd", false);
        session.receive("ef", true);
        session.receive("next", true);

        Assertions.assertEquals(List.of("abcdef:true", "next:true"), recorder.messages);
    }

    @Test
    void aMessageInOneFramePastTheLimitIsRefused() {
        session.receive("123456789", true);

        Assertions.assertEquals(List.of(), recorder.messages, "A message past the limit was handed on");
        assertClosedWith(WebSocketCloseStatus.MESSAGE_TOO_BIG);
    }

    @Test
    void aMessageInPiecesPastTheLimitIsRefusedWithWhatIsLeftOfIt() {
        session.receive("12345", false);
        session.receive("6789", false);
        session.receive("0", true); // the rest of the refused message, which arrived before the close did

        Assertions.assertEquals(List.of(), recorder.messages, "A piece of a refused message was handed on");
        assertClosedWith(WebSocketCloseStatus.MESSAGE_TOO_BIG);
    }

    @Test
    void aReceiverWhichThrowsLeavesNothingToTheNextMessage() {
        final boolean[] thrown = new boolean[1];
        final WholeTextReceiver receiver = new WholeTextReceiver(MAX_MESSAGE_LENGTH, (s, message, last) -> {
            recorder.text(s, message, last);
            if (!thrown[0]) {
                thrown[0] = true;
                throw new IllegalStateException("Not today");
            }
        });

        Assertions.assertThrows(IllegalStateException.class, () -> {
            receiver.text(session, "ab", false);
            receiver.text(session, "cd", true);
        });
        receiver.text(session, "next", true);

        Assertions.assertEquals(List.of("abcd:true", "next:true"), recorder.messages);
    }

    /**
     * What the fast path is for: a message which comes in one frame costs no allocation, whether or not the
     * session has put a message together before.
     */
    @Test
    void aMessageInOneFrameAllocatesNothing() {
        final int[] received = new int[1];
        final ClientSession counted = new ClientSessions(null, null).newSession(
                new ClientSessionContext(new NoWritingResult(), new WholeTextReceiver(MAX_MESSAGE_LENGTH,
                        (s, message, last) -> received[0]++), null, channel, 0));
        final String message = "whole";

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final int messages = 10_000;
        for (int round = 0; round < 2; round++) { // before and after a message in pieces
            for (int i = 0; i < messages; i++) {
                counted.receive(message, true);
            }

            final long before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < messages; i++) {
                counted.receive(message, true);
            }
            final long allocated = threads.getThreadAllocatedBytes(thread) - before;

            Assertions.assertTrue(allocated < messages, // less than a byte a message: what measuring costs
                    allocated + " bytes allocated by " + messages + " messages");

            counted.receive("ab", false);
            counted.receive("cd", true);
        }
        Assertions.assertEquals(4 * messages + 2, received[0]);
    }

    private void assertClosedWith(final WebSocketCloseStatus status) {
        final CloseWebSocketFrame close = channel.readOutbound();
        try {
            Assertions.assertNotNull(close, "The session was not closed with a status");
            Assertions.assertEquals(status.code(), close.statusCode());
        } finally {
            if (close != null) {
                close.release();
            }
        }
    }

    private static final class NoWritingResult implements WritingResult {
        @Override
        public void onWriteSuccess(final ClientSession session) {
        }

        @Override
        public void onWriteBackPressure(final ClientSession session) {
        }

        @Override
        public void onWriteError(final ClientSession session,
                                 final Throwable error) {
        }
    }
}
