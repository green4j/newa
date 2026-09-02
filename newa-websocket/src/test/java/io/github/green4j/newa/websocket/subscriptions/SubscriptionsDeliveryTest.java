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

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.ClientSessionContext;
import io.github.green4j.newa.websocket.ClientSessions;
import io.github.green4j.newa.websocket.ClientSessionsListener;
import io.github.green4j.newa.websocket.WritingResult;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end delivery checks over real, multi-threaded Netty event loops (the local
 * transport), with publishers running on their own threads throughout. EmbeddedChannel
 * can not cover this: it reports inEventLoop() == true for every thread and runs
 * everything inline, so the queueing which orders a snapshot against a concurrent
 * update never happens there.
 *
 * <p>Every test asserts the same contract on the frames as they arrive on the wire:
 * a snapshot first, then updates in order, with no publication missing between them.
 * A duplicate is tolerated - a publication may be both in the snapshot and delivered.
 */
class SubscriptionsDeliveryTest {
    private static final String SNAPSHOT = "S";
    private static final String UPDATE = "U";

    private static final long PUBLICATION_PAUSE_NANOS = 50_000L; // ~20k publications/sec
    private static final long RUN_MS = 150L;
    private static final long SETTLE_MS = 500L;

    private static final class ValueSubscriptions extends EntitySubscriptions {
        // Plain, non-volatile on purpose: its visibility to a session subscribing
        // concurrently must come from the publication sequence edge only.
        private int value;

        ValueSubscriptions(final String entityId) {
            super(entityId);
        }

        void publishValue(final int newValue) {
            value = newValue; // the state must be mutated before the fan-out
            publish(session -> session.send(UPDATE + ":" + entityId + ":" + newValue));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            session.send(SNAPSHOT + ":" + entityId + ":" + value);
        }
    }

    private static final class TestChannel extends Channel<ValueSubscriptions> {
        @Override
        protected ValueSubscriptions newEntitySubscriptions(final String entityId) {
            return new ValueSubscriptions(entityId);
        }
    }

    private static final class Peer {
        private final List<String> received = Collections.synchronizedList(new ArrayList<>());
        private ClientSession session;

        private List<String> snapshotOfReceived() {
            synchronized (received) {
                return new ArrayList<>(received);
            }
        }
    }

    /**
     * Publishes to one entity continuously on its own thread until stopped. One thread
     * per entity - publications of a single entity must be serialized.
     */
    private static final class Publisher implements AutoCloseable {
        private final ValueSubscriptions entity;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread thread;

        Publisher(final ValueSubscriptions entity) {
            this.entity = entity;
            this.thread = new Thread(this::run, "publisher-" + entity.entityId());
            this.thread.start();
        }

        private void run() {
            int value = 0;
            while (!stopped.get()) {
                entity.publishValue(++value);
                LockSupport.parkNanos(PUBLICATION_PAUSE_NANOS);
            }
        }

        @Override
        public void close() throws InterruptedException {
            stopped.set(true);
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private final AtomicInteger droppedFrames = new AtomicInteger();
    private final BlockingQueue<io.netty.channel.Channel> accepted = new LinkedBlockingQueue<>();

    private final WritingResult writingResult = new WritingResult() {
        @Override
        public void onWriteSuccess(final ClientSession session) {
        }

        @Override
        public void onWriteBackPressure(final ClientSession session) {
            droppedFrames.incrementAndGet();
        }

        @Override
        public void onWriteError(final ClientSession session, final Throwable error) {
            droppedFrames.incrementAndGet();
        }
    };

    private EventLoopGroup serverGroup;
    private EventLoopGroup clientGroup;
    private io.netty.channel.Channel serverChannel;
    private LocalAddress address;
    private ClientSessions clientSessions;
    private TestChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        serverGroup = new MultiThreadIoEventLoopGroup(4, LocalIoHandler.newFactory());
        clientGroup = new MultiThreadIoEventLoopGroup(2, LocalIoHandler.newFactory());

        address = new LocalAddress("newa-subscriptions-" + System.nanoTime());

        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(LocalServerChannel.class)
                // keep the channel writable - ClientSession drops a frame when it is not,
                // and a dropped frame would look exactly like a lost publication
                .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 8 << 20)
                .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 4 << 20)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel ch) {
                        accepted.add(ch);
                    }
                })
                .bind(address).sync().channel();

        clientSessions = new ClientSessions(new ClientSessionsListener() {
            @Override
            public void onSessionOpened(final ClientSession session) {
            }

            @Override
            public void onSessionClosed(final ClientSession session) {
            }
        });

        channel = new TestChannel();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.close();
        serverChannel.close().sync();
        serverGroup.shutdownGracefully().sync();
        clientGroup.shutdownGracefully().sync();
    }

    private Peer connect() throws Exception {
        final Peer peer = new Peer();

        new Bootstrap()
                .group(clientGroup)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx,
                                                        final TextWebSocketFrame msg) {
                                peer.received.add(msg.text());
                            }
                        });
                    }
                })
                .connect(address).sync();

        final io.netty.channel.Channel serverSide = accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(serverSide, "the server did not accept the connection");

        peer.session = clientSessions.newSession(
                new ClientSessionContext(writingResult, null, serverSide, 0)
        );
        ClientSessionSubscriptions.attach(peer.session, null);

        return peer;
    }

    /**
     * Asserts the delivery contract on the frames of one entity: a snapshot comes first,
     * updates follow in order, and nothing between the snapshot and the updates is missing.
     *
     * @param peer the connection to inspect.
     * @param entityId the entity whose frames are checked.
     */
    private void assertDelivery(final Peer peer, final String entityId) {
        assertEquals(0, droppedFrames.get(),
                "the transport dropped frames, the run is inconclusive");

        final List<String> messages = new ArrayList<>();
        for (final String message : peer.snapshotOfReceived()) {
            if (message.split(":")[1].equals(entityId)) {
                messages.add(message);
            }
        }

        assertFalse(messages.isEmpty(), "nothing was delivered for " + entityId);

        int snapshotValue = -1;
        int expectedNext = -1;
        boolean firstUpdateOfSegment = false;

        for (final String message : messages) {
            final String[] parts = message.split(":");
            final int value = Integer.parseInt(parts[2]);

            if (SNAPSHOT.equals(parts[0])) {
                snapshotValue = value;
                expectedNext = value + 1;
                firstUpdateOfSegment = true;
                continue;
            }

            assertTrue(expectedNext >= 0, "an update arrived before any snapshot: " + message);

            if (firstUpdateOfSegment) {
                // the first update may repeat what the snapshot already carried, but it
                // must not skip anything the snapshot did not contain
                assertTrue(value <= snapshotValue + 1,
                        "publications " + (snapshotValue + 1) + ".." + (value - 1)
                                + " were neither in the snapshot (" + snapshotValue
                                + ") nor delivered; stream: " + messages);
                firstUpdateOfSegment = false;
            } else {
                assertEquals(expectedNext, value,
                        "updates must arrive in order and without holes; stream: " + messages);
            }

            expectedNext = value + 1;
        }
    }

    private static void assertNoMoreUpdates(final Peer peer) throws InterruptedException {
        Thread.sleep(SETTLE_MS);
        final int before = peer.snapshotOfReceived().size();
        Thread.sleep(SETTLE_MS);
        assertEquals(before, peer.snapshotOfReceived().size(),
                "updates kept arriving after the session had been unsubscribed");
    }

    @Test
    void shouldDeliverSnapshotThenUpdatesWhenSubscribingOffTheEventLoop() throws Exception {
        final Peer peer = connect();
        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            Thread.sleep(RUN_MS);
            channel.subscribe(peer.session, "AA"); // from a foreign thread - hops onto the event loop
            Thread.sleep(RUN_MS);
        }

        Thread.sleep(SETTLE_MS);
        assertDelivery(peer, "AA");
    }

    @Test
    void shouldDeliverSnapshotThenUpdatesWhenSubscribingOnTheEventLoop() throws Exception {
        final Peer peer = connect();
        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            Thread.sleep(RUN_MS);
            // the normal path: a Receiver runs on the event loop of the session
            peer.session.executor().execute(() -> channel.subscribe(peer.session, "AA"));
            Thread.sleep(RUN_MS);
        }

        Thread.sleep(SETTLE_MS);
        assertDelivery(peer, "AA");
    }

    @Test
    void shouldDeliverToManySessionsSubscribingConcurrently() throws Exception {
        final int sessions = 8;

        final List<Peer> peers = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            peers.add(connect());
        }

        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            Thread.sleep(RUN_MS);

            final List<Thread> subscribers = new ArrayList<>();
            for (final Peer peer : peers) {
                final Thread subscriber = new Thread(() -> channel.subscribe(peer.session, "AA"));
                subscribers.add(subscriber);
                subscriber.start();
            }
            for (final Thread subscriber : subscribers) {
                subscriber.join();
            }

            Thread.sleep(RUN_MS);
        }

        Thread.sleep(SETTLE_MS);
        for (final Peer peer : peers) {
            assertDelivery(peer, "AA");
        }
    }

    @Test
    void shouldKeepEveryEntityStreamCorrectForASessionSubscribedToMany() throws Exception {
        final Peer peer = connect();

        final ValueSubscriptions a = channel.getOrCreateEntitySubscriptions("AA");
        final ValueSubscriptions b = channel.getOrCreateEntitySubscriptions("BB");
        final ValueSubscriptions c = channel.getOrCreateEntitySubscriptions("CC");

        try (Publisher pa = new Publisher(a);
                Publisher pb = new Publisher(b);
                Publisher pc = new Publisher(c)) {

            Thread.sleep(RUN_MS);
            channel.subscribe(peer.session, List.<CharSequence>of("AA", "BB", "CC"), new ArrayList<>());
            Thread.sleep(RUN_MS);
        }

        Thread.sleep(SETTLE_MS);
        assertDelivery(peer, "AA");
        assertDelivery(peer, "BB");
        assertDelivery(peer, "CC");
    }

    @Test
    void shouldStopUpdatesAfterUnsubscribe() throws Exception {
        final Peer peer = connect();
        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            channel.subscribe(peer.session, "AA");
            Thread.sleep(RUN_MS);

            assertDelivery(peer, "AA");

            channel.unsubscribe(peer.session, "AA");
            assertNoMoreUpdates(peer);
        }
    }

    @Test
    void shouldStopUpdatesAfterUnsubscribeAll() throws Exception {
        final Peer peer = connect();
        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            channel.subscribe(peer.session, "AA");
            Thread.sleep(RUN_MS);

            assertDelivery(peer, "AA");

            channel.unsubscribeAll(peer.session);
            assertNoMoreUpdates(peer);
        }
    }

    @Test
    void shouldDeliverAFreshSnapshotOnResubscribe() throws Exception {
        final Peer peer = connect();
        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        try (Publisher publisher = new Publisher(entity)) {
            channel.subscribe(peer.session, "AA");
            Thread.sleep(RUN_MS);

            channel.unsubscribe(peer.session, "AA");
            Thread.sleep(RUN_MS); // the stream goes on without this session

            channel.subscribe(peer.session, "AA");
            Thread.sleep(RUN_MS);
        }

        Thread.sleep(SETTLE_MS);

        // two segments, each of them a snapshot followed by an uninterrupted run of updates
        final List<String> messages = peer.snapshotOfReceived();
        assertEquals(2, messages.stream().filter(m -> m.startsWith(SNAPSHOT + ":")).count(),
                "a resubscription must produce a second snapshot");
        assertDelivery(peer, "AA");
    }
}
