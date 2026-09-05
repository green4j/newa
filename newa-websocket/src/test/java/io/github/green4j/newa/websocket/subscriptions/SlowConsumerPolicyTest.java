/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to a session which can not take a frame right now. By default it is closed;
 * with {@code withSkipOnBackPressure()} the frame is skipped, the session survives, and the
 * subscriptions layer re-sends a snapshot once the channel drains - so the skipped frames
 * leave no hole in the stream.
 *
 * <p>Writability is driven through {@link io.netty.channel.ChannelOutboundBuffer}'s
 * user defined flag, so the transitions are exact instead of load dependent.
 */
class SlowConsumerPolicyTest {
    private static final int WRITABILITY_FLAG = 1;
    private static final long TIMEOUT_MS = 10_000L;

    private static final class ValueSubscriptions extends EntitySubscriptions {
        private int value;

        ValueSubscriptions(final String entityId) {
            super(entityId);
        }

        void publishValue(final int newValue) {
            value = newValue;
            publish(session -> session.send("U:" + newValue));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            session.send("S:" + value);
        }
    }

    private static final class TestChannel extends Channel<ValueSubscriptions> {
        @Override
        protected ValueSubscriptions newEntitySubscriptions(final String entityId) {
            return new ValueSubscriptions(entityId);
        }
    }

    private static void awaitTrue(final String what, final BooleanSupplier condition)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private io.netty.channel.Channel server;
    private WebSocket client;

    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final List<String> stages = Collections.synchronizedList(new ArrayList<>());
    private volatile ClientSession session;

    private TestChannel channel;

    private void startServer(final boolean skipOnBackPressure) throws Exception {
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workers = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final SubscriptionWsApiBuilder builder = new SubscriptionWsApiBuilder(1)
                .withObservers((SubscriptionsWsApiObserverFactory) () -> new SubscriptionsWsApiObserver() {
                    @Override
                    public void onSessionOpened(final ClientSession opened) {
                        session = opened;
                        stages.add("opened");
                    }

                    @Override
                    public void onWriteBackPressure(final int bytes) {
                        stages.add("backPressure");
                    }

                    @Override
                    public void onWriteResumed() {
                        stages.add("resumed");
                    }

                    @Override
                    public void onResynced(final int entities) {
                        stages.add("resynced:" + entities);
                    }

                    @Override
                    public void onSessionClosed(final long durationNanos) {
                        stages.add("closed");
                    }
                });

        channel = new TestChannel();
        if (skipOnBackPressure) {
            builder.withSkipOnBackPressure();
        }

        final WsApi api = builder.build();

        server = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        final ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536, true));
                        p.addLast(new WsApiHandler(api, (c, cause) -> { }));
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

        final int port = ((InetSocketAddress) server.localAddress()).getPort();
        final CountDownLatch opened = new CountDownLatch(1);

        client = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + port + api.websocketPath()),
                        new WebSocket.Listener() {
                            @Override
                            public void onOpen(final WebSocket webSocket) {
                                webSocket.request(Long.MAX_VALUE);
                                opened.countDown();
                            }

                            @Override
                            public CompletionStage<?> onText(final WebSocket webSocket,
                                                             final CharSequence data,
                                                             final boolean last) {
                                received.add(data.toString());
                                return null;
                            }
                        })
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertTrue(opened.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        awaitTrue("the server to register the session", () -> session != null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.abort();
        }
        if (channel != null) {
            channel.close();
        }
        server.close().sync();
        boss.shutdownGracefully().sync();
        workers.shutdownGracefully().sync();
    }

    private void setWritable(final boolean writable) throws InterruptedException {
        final io.netty.channel.Channel ch = session.channel();
        ch.eventLoop().execute(() -> {
            final io.netty.channel.ChannelOutboundBuffer buffer = ch.unsafe().outboundBuffer();
            if (buffer != null) {
                buffer.setUserDefinedWritability(WRITABILITY_FLAG, writable);
            }
        });
        awaitTrue("the channel to become " + (writable ? "writable" : "unwritable"),
                () -> ch.isWritable() == writable);
    }

    private List<String> snapshotOfReceived() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    private List<String> snapshotOfStages() {
        synchronized (stages) {
            return new ArrayList<>(stages);
        }
    }

    @Test
    void shouldCloseASessionWhichCanNotTakeAFrameByDefault() throws Exception {
        startServer(false);

        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");
        channel.subscribe(session, "AA");
        awaitTrue("the snapshot", () -> snapshotOfReceived().contains("S:0"));

        setWritable(false);
        entity.publishValue(1);

        awaitTrue("the session to be closed", () -> session.isClosed());
        assertFalse(snapshotOfReceived().contains("U:1"));

        final List<String> observed = snapshotOfStages();
        assertEquals(List.of("opened", "backPressure", "closed"), observed,
                "the frame which did not go out is reported, and the session ends right there");
    }

    @Test
    void shouldKeepASessionAndResendTheSnapshotOnceItCatchesUp() throws Exception {
        startServer(true);

        final ValueSubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");
        channel.subscribe(session, "AA");
        awaitTrue("the first snapshot", () -> snapshotOfReceived().contains("S:0"));

        entity.publishValue(1);
        awaitTrue("the first update", () -> snapshotOfReceived().contains("U:1"));

        setWritable(false);
        for (int value = 2; value <= 5; value++) {
            entity.publishValue(value); // skipped, the session must survive
        }
        Thread.sleep(200);

        assertFalse(session.isClosed(), "the session must not be closed while it is lagging");
        final List<String> whileLagging = snapshotOfReceived();
        assertEquals(List.of("S:0", "U:1"), whileLagging, "nothing may be delivered while lagging");

        setWritable(true);

        // catching up must produce a fresh snapshot carrying the state the skipped frames had
        awaitTrue("the re-sent snapshot", () -> snapshotOfReceived().contains("S:5"));

        entity.publishValue(6);
        awaitTrue("the update after the re-sync", () -> snapshotOfReceived().contains("U:6"));

        assertEquals(List.of("S:0", "U:1", "S:5", "U:6"), snapshotOfReceived());
        assertFalse(session.isClosed());

        assertEquals(
                List.of("opened",
                        "backPressure", "backPressure", "backPressure", "backPressure",
                        "resumed", "resynced:1"),
                snapshotOfStages(),
                "every skipped frame is reported, and catching up is reported once, with what it re-sent"
        );
    }

    @Test
    void shouldNotResendTheSnapshotWhenNothingWasSkipped() throws Exception {
        startServer(true);

        channel.getOrCreateEntitySubscriptions("AA");
        channel.subscribe(session, "AA");
        awaitTrue("the snapshot", () -> snapshotOfReceived().contains("S:0"));

        // a writability transition without a single skipped frame must be a no-op
        setWritable(false);
        setWritable(true);
        Thread.sleep(200);

        assertEquals(List.of("S:0"), snapshotOfReceived());
        assertNotNull(session);

        assertEquals(List.of("opened"), snapshotOfStages(),
                "a writability transition without a skipped frame is nothing to report");
    }
}
