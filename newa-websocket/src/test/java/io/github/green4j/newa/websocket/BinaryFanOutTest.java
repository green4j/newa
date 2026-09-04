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

import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The binary half of a fan-out: every session is given a frame of its own, the frame is a binary one, and
 * the buffer is owned exactly as the text twin of the call owns it - taken over by the {@code AndRelease}
 * form, left to the caller by the other.
 */
class BinaryFanOutTest {
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private static ByteBuf payload(final String bytes) {
        return Unpooled.copiedBuffer(bytes, StandardCharsets.UTF_8);
    }

    private ClientSession newSession(final WsApi api) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        return api.newSession(
                new ClientSessionContext(
                        api,
                        null,
                        channel,
                        0 // no pinger
                )
        );
    }

    private static String readBinary(final ClientSession session) {
        final Object written = ((EmbeddedChannel) session.channel()).readOutbound();
        if (written == null) {
            return null;
        }
        Assertions.assertInstanceOf(BinaryWebSocketFrame.class, written, "not a binary frame");
        final BinaryWebSocketFrame frame = (BinaryWebSocketFrame) written;
        try {
            return frame.content().toString(StandardCharsets.UTF_8);
        } finally {
            frame.release();
        }
    }

    private String readBinary(final int channel) {
        final Object written = channels.get(channel).readOutbound();
        Assertions.assertInstanceOf(BinaryWebSocketFrame.class, written, "not a binary frame");
        final BinaryWebSocketFrame frame = (BinaryWebSocketFrame) written;
        try {
            return frame.content().toString(StandardCharsets.UTF_8);
        } finally {
            frame.release();
        }
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void aBroadcastGivesEverySessionABinaryFrameOfItsOwn() {
        final WsApi api = new WsApiBuilder(1).build();

        newSession(api);
        newSession(api);
        newSession(api);

        final ByteBuf frame = payload("bytes");
        api.broadcastBinaryAndRelease(frame);

        Assertions.assertEquals(3, frame.refCnt(),
                "one retained duplicate per session, and the buffer itself already released");

        for (int i = 0; i < channels.size(); i++) {
            Assertions.assertEquals("bytes", readBinary(i));
        }

        Assertions.assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void aBroadcastWhichIsNotHandedOverLeavesTheBufferToTheCaller() {
        final WsApi api = new WsApiBuilder(1).build();

        newSession(api);

        final ByteBuf frame = payload("bytes");
        api.broadcastBinary(frame);
        api.broadcastBinary(frame); // the same buffer again, which handing it over would have made
        // impossible

        Assertions.assertEquals("bytes", readBinary(0));
        Assertions.assertEquals("bytes", readBinary(0));

        Assertions.assertEquals(1, frame.refCnt(), "the buffer is still the caller's");
        frame.release();
    }

    @Test
    void aPublicationGivesEverySubscriberABinaryFrameOfItsOwn() {
        final WsApi api = new WsApiBuilder(1).build();
        final EntitySubscriptions subscriptions = new EntitySubscriptions("E");

        final ClientSession one = newSession(api);
        final ClientSession two = newSession(api);

        subscriptions.add(one);
        subscriptions.add(two);

        final ByteBuf frame = payload("E=1");
        final long sequence = subscriptions.publishBinaryAndRelease(frame);

        Assertions.assertEquals(1, sequence, "a publication is numbered whichever way it is made");
        Assertions.assertEquals(2, frame.refCnt());

        Assertions.assertEquals("E=1", readBinary(one));
        Assertions.assertEquals("E=1", readBinary(two));

        Assertions.assertEquals(0, frame.refCnt(), "every duplicate is accounted for");
    }

    @Test
    void aPublicationWhichIsNotHandedOverLeavesTheBufferToTheCaller() {
        final WsApi api = new WsApiBuilder(1).build();
        final EntitySubscriptions subscriptions = new EntitySubscriptions("E");

        final ClientSession session = newSession(api);
        subscriptions.add(session);

        final ByteBuf frame = payload("E=1");
        subscriptions.publishBinary(frame);

        Assertions.assertEquals("E=1", readBinary(session));
        Assertions.assertEquals(1, frame.refCnt(), "the buffer is still the caller's");
        frame.release();
    }

    /**
     * A walk numbers nothing: it is for an administrative broadcast, where a subscriber counting holes by
     * the sequence must not see one.
     */
    @Test
    void aWalkOverTheSubscribersSendsBinaryWithoutNumberingAnything() {
        final WsApi api = new WsApiBuilder(1).build();
        final EntitySubscriptions subscriptions = new EntitySubscriptions("E");

        final ClientSession session = newSession(api);
        subscriptions.add(session);

        final ByteBuf frame = payload("notice");
        Assertions.assertEquals(1, subscriptions.forEachSessionBinaryAndRelease(frame));

        Assertions.assertEquals("notice", readBinary(session));
        Assertions.assertEquals(0, frame.refCnt());
        Assertions.assertEquals(0, subscriptions.publicationSequence(), "a walk is not a publication");
    }
}
