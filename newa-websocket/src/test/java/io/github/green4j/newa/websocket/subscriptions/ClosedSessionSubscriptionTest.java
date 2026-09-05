/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.ClientSessionContext;
import io.github.green4j.newa.websocket.ClientSessions;
import io.github.green4j.newa.websocket.ClientSessionsListener;
import io.github.green4j.newa.websocket.WritingResult;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A subscription asked for from another thread is scheduled onto the event loop of the session, so it can
 * land after the connection dropped. The unsubscribing of a session which goes away runs exactly once,
 * inside ClientSession.close(), and nothing would ever take out a session which entered an entity after
 * that: it would be published to, and held, forever.
 *
 * <p>EmbeddedChannel can not show it - it answers inEventLoop() == true to any thread, so the deferred path
 * is unreachable there. The local transport gives a real event loop of its own, which is what makes the
 * order below deterministic: both tasks are queued from the test thread, in that order.
 */
class ClosedSessionSubscriptionTest {
    private static final class TestChannel extends Channel<EntitySubscriptions> {
        @Override
        protected EntitySubscriptions newEntitySubscriptions(final String entityId) {
            return new EntitySubscriptions(entityId);
        }
    }

    private static final WritingResult NO_WRITING_RESULT = new WritingResult() {
        @Override
        public void onWriteSuccess(final ClientSession session) {
        }

        @Override
        public void onWriteBackPressure(final ClientSession session) {
        }

        @Override
        public void onWriteError(final ClientSession session, final Throwable error) {
        }
    };

    private final BlockingQueue<io.netty.channel.Channel> accepted = new LinkedBlockingQueue<>();

    private EventLoopGroup serverGroup;
    private EventLoopGroup clientGroup;
    private io.netty.channel.Channel serverChannel;
    private LocalAddress address;
    private ClientSessions clientSessions;
    private TestChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        serverGroup = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        clientGroup = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());

        address = new LocalAddress("newa-closed-session-" + System.nanoTime());

        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel ch) {
                        accepted.add(ch);
                    }
                })
                .bind(address).sync().channel();

        // what SubscriptionsWsApi does per session: the bookkeeping in place when it opens, and everything
        // it subscribed to taken back when it closes
        clientSessions = new ClientSessions(new ClientSessionsListener() {
            @Override
            public void onSessionOpened(final ClientSession session) {
                ClientSessionSubscriptions.attach(session, null);
            }

            @Override
            public void onSessionClosed(final ClientSession session) {
                ClientSessionSubscriptions.getClientSessionSubscriptions(session).unsubscribeAll();
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

    @Test
    void shouldNotSubscribeASessionWhichClosedBeforeTheScheduledSubscribingRan() throws Exception {
        final ClientSession session = openSession();
        final EntitySubscriptions entity = channel.getOrCreateEntitySubscriptions("AA");

        assertFalse(session.channel().eventLoop().inEventLoop(),
                "the deferred path is the point of this test");

        session.close(); // queues the unsubscribing onto the event loop of the session
        assertEquals(0, channel.subscribe(session, "AA")); // queues the subscribing behind it

        drainEventLoop(session);

        assertEquals(0, entity.numberOfSubscribedSessions(),
                "a closed session came back into the subscribers, and nothing would take it out again");
        assertEquals(0, ClientSessionSubscriptions.of(session).numberOfSubscribedEntities());
        assertFalse(channel.isSubscribed(session));
    }

    @Test
    void shouldStillSubscribeASessionWhichIsAlive() throws Exception {
        final ClientSession session = openSession();

        final List<CharSequence> unknown = new ArrayList<>();
        assertEquals(0, channel.subscribe(session, List.<CharSequence>of("AA"), unknown),
                "0 comes back from another thread whatever the outcome");

        drainEventLoop(session);

        assertEquals(1, channel.getEntitySubscriptions("AA").numberOfSubscribedSessions());
        assertEquals(1, ClientSessionSubscriptions.of(session).numberOfSubscribedEntities());
    }

    private ClientSession openSession() throws Exception {
        new Bootstrap()
                .group(clientGroup)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel ch) {
                    }
                })
                .connect(address).sync();

        final io.netty.channel.Channel serverSide = accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(serverSide, "the server did not accept the connection");

        return clientSessions.newSession(
                new ClientSessionContext(NO_WRITING_RESULT, null, null, serverSide, 0)
        );
    }

    private static void drainEventLoop(final ClientSession session) throws Exception {
        // everything queued above has run by the time this one does - one loop, one queue
        session.channel().eventLoop().submit(() -> {
        }).get(10, TimeUnit.SECONDS);
    }
}
