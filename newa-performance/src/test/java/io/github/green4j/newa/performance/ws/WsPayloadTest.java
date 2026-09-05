/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message is the contract between the three servers and the client. The servers have to produce the same
 * bytes from different code, and the client has to find two fields in whatever comes back without decoding
 * it - a field it could not find, or found in the wrong place, would be read as a wrong number rather than
 * as an error.
 */
public class WsPayloadTest {

    /**
     * What is published is one event object, small enough that a run is limited by how many of them the
     * server can push rather than by how many bytes. A fan-out measured with a document would be measuring
     * the bandwidth of the link.
     */
    @Test
    public void aMessageIsOneEventObjectOfTheSizeAnEventIs() {
        final String text = text(WsPayload.render(0, 1, 1, WsPayload.padding(WsPayload.DEFAULT_SIZE)));

        assertTrue(text.startsWith("{\"type\":\"event\",\"seq\":"), text);
        assertTrue(text.endsWith("}"), text);
        assertEquals(1, count(text, '{'), "one object, not a document of them: " + text);
        assertEquals(1, count(text, '}'), "one object, not a document of them: " + text);

        assertTrue(WsPayload.DEFAULT_SIZE >= 120 && WsPayload.DEFAULT_SIZE <= 256,
                "an event, not a document: " + WsPayload.DEFAULT_SIZE + " bytes");
        assertTrue(WsPayload.MIN_SIZE <= WsPayload.DEFAULT_SIZE,
                "the default has to be a size the layout can render: " + WsPayload.MIN_SIZE);
    }

    /**
     * The size a run asks for is what the padding is worked out from, and it is nominal: a sequence number
     * is as wide as it happens to be, which is what ordinary JSON does and what a fixed width layout would
     * have hidden. What matters is that it is the asked-for size at the sequences a run reaches, and never
     * wildly off.
     */
    @Test
    public void aMessageIsAboutTheSizeTheRunAskedFor() {
        final String pad = WsPayload.padding(WsPayload.DEFAULT_SIZE);
        for (final long sequence : new long[]{1L, 1_000L, 1_000_000L, 1_000_000_000L}) {
            final int size = WsPayload.render(0, sequence, System.nanoTime(), pad).length;
            assertTrue(Math.abs(size - WsPayload.DEFAULT_SIZE) <= 16,
                    "at sequence " + sequence + " a message was " + size + " bytes");
        }
        assertThrows(IllegalArgumentException.class, () -> WsPayload.padding(WsPayload.MIN_SIZE - 1));
    }

    @Test
    public void theTwoFieldsTheClientNeedsComeBackAsTheyWentIn() {
        final byte[] message = WsPayload.render(2, 123_456L, 9_876_543_210L, "");

        final ByteBuf frame = Unpooled.wrappedBuffer(message);
        final int body = WsPayload.bodyStart(frame);
        assertEquals(0, body, "A message which is only itself starts at its first byte");
        assertEquals(123_456L, WsPayload.readSequence(frame, body));
        assertEquals(9_876_543_210L, WsPayload.readPublishedNanos(frame, body));
        assertEquals(2, WsPayload.readChannel(frame, body));
    }

    @Test
    public void theBodyIsFoundBehindWhateverIsPutInFrontOfIt() {
        final byte[] message = WsPayload.render(1, 7L, 11L, "");

        // what a STOMP server sends: a frame whose headers carry a message id of a width which grows
        // during a run, so the body cannot be found by counting bytes
        final String stomp = "MESSAGE\ndestination:/topic/c01\nmessage-id:abc-12345\n"
                + "content-type:application/json\n\n" + text(message);
        final ByteBuf frame = Unpooled.wrappedBuffer(stomp.getBytes(StandardCharsets.US_ASCII));

        final int body = WsPayload.bodyStart(frame);
        assertTrue(body > 0);
        assertEquals(7L, WsPayload.readSequence(frame, body));
        assertEquals(11L, WsPayload.readPublishedNanos(frame, body));
        assertEquals(1, WsPayload.readChannel(frame, body));
    }

    @Test
    public void aMessageDependsOnTheSequenceItCarries() {
        final String one = text(WsPayload.render(0, 1, 0, ""));
        final String two = text(WsPayload.render(0, 2, 0, ""));

        assertTrue(!one.equals(two), "A message a server could hoist out of the run is not a measurement");
    }

    @Test
    public void channelsAreNamedTheSameWayEverywhere() {
        assertEquals("c00", WsPayload.channelId(0));
        assertEquals("c01", WsPayload.channelId(1));
        assertEquals("c42", WsPayload.channelId(42));
        assertThrows(IllegalArgumentException.class, () -> WsPayload.channelId(100));
    }

    private static int count(final String text,
                             final char character) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == character) {
                count++;
            }
        }
        return count;
    }

    private static String text(final byte[] message) {
        return new String(message, StandardCharsets.US_ASCII);
    }
}
