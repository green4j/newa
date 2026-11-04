/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;

/**
 * {@link ClientSession#sendText(CharSequence)} and its twin with a charset: the text is encoded right into a
 * buffer of the channel's allocator, of exactly the size of the frame, the way {@link String#getBytes(Charset)}
 * encodes it - a character of four bytes as four, a surrogate which is not a character as {@code ?}.
 */
class SendTextTest {
    private static final String TEXT = "a\u00e9\u20ac" + new String(Character.toChars(0x1F600))
            + " \ud83d b"; // the surrogate on its own is not a character

    private static final String DEFAULT = "default";

    private final EmbeddedChannel channel = new EmbeddedChannel();

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    /**
     * @param charset the text is sent in, or {@code default} for the one {@code sendText(CharSequence)} sends
     *                it in.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DEFAULT, "UTF-8", "ISO-8859-1", "UTF-16BE"})
    void aTextIsEncodedIntoAFrameOfItsExactSize(final String charset) {
        final ClientSession session = new ClientSessions(null, null).newSession(
                new ClientSessionContext(new NoWritingResult(), null, null, channel, 0));

        if (DEFAULT.equals(charset)) {
            session.sendText(TEXT);
        } else {
            session.sendText(TEXT, Charset.forName(charset));
        }

        final TextWebSocketFrame frame = channel.readOutbound();
        try {
            final ByteBuf content = frame.content();
            final byte[] sent = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), sent);

            Assertions.assertArrayEquals(TEXT.getBytes(Charset.forName(DEFAULT.equals(charset) ? "UTF-8" : charset)),
                    sent);
            if (DEFAULT.equals(charset) || "UTF-8".equals(charset)) {
                Assertions.assertEquals(sent.length, content.capacity(), "More than the frame was allocated");
            }
        } finally {
            frame.release();
        }
    }

    /**
     * The text is read twice, once to count its bytes and once to write them, and a text of the caller's may
     * fail the second time: the buffer allocated in between is released, and nothing is sent.
     */
    @Test
    void aTextWhichFailsToBeReadLeavesNoBufferBehind() {
        final ByteBuf[] allocated = new ByteBuf[1];
        channel.config().setAllocator(new AbstractByteBufAllocator(false) {
            @Override
            protected ByteBuf newHeapBuffer(final int initialCapacity, final int maxCapacity) {
                allocated[0] = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
                return allocated[0];
            }

            @Override
            protected ByteBuf newDirectBuffer(final int initialCapacity, final int maxCapacity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isDirectBufferPooled() {
                return false;
            }
        });
        final ClientSession session = new ClientSessions(null, null).newSession(
                new ClientSessionContext(new NoWritingResult(), null, null, channel, 0));
        final int[] reads = new int[1];
        final CharSequence failing = new CharSequence() {
            @Override
            public int length() {
                return 1;
            }

            @Override
            public char charAt(final int index) {
                if (++reads[0] > 1) {
                    throw new IllegalStateException("Changed under the session");
                }
                return 'a';
            }

            @Override
            public CharSequence subSequence(final int start, final int end) {
                throw new UnsupportedOperationException();
            }
        };

        Assertions.assertThrows(IllegalStateException.class, () -> session.sendText(failing));

        Assertions.assertEquals(0, allocated[0].refCnt(), "The buffer was left unreleased");
        Assertions.assertNull(channel.readOutbound(), "A frame was sent");
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
