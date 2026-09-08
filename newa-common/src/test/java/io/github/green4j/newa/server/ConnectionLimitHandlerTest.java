/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ConnectionLimitHandlerTest {
    private static final String HOST = "127.0.0.1";

    /**
     * Records whether the connection was ever announced to the handlers behind the limit.
     */
    private static final class Announced extends ChannelInboundHandlerAdapter {
        private boolean active;

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            active = true;
            ctx.fireChannelActive();
        }
    }

    /**
     * An EmbeddedChannel is active the moment it is built, which is the event the limit acts on.
     *
     * @param limit shared by every channel of one server, as a real one shares it
     * @return the connection, open or already closed
     */
    private static EmbeddedChannel connect(final ConnectionLimitHandler limit) {
        return new EmbeddedChannel(limit, new Announced());
    }

    @Test
    public void aConnectionWithinTheLimitIsKept() {
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(2);

        final EmbeddedChannel first = connect(limit);
        final EmbeddedChannel second = connect(limit);

        Assertions.assertTrue(first.isOpen());
        Assertions.assertTrue(second.isOpen());
        Assertions.assertEquals(2, limit.connections());
        Assertions.assertEquals(0, limit.refused());

        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    @Test
    public void theOneAboveItIsClosedAsItArrives() {
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(1);

        final EmbeddedChannel kept = connect(limit);
        final EmbeddedChannel refused = connect(limit);

        Assertions.assertTrue(kept.isOpen());
        Assertions.assertFalse(refused.isOpen(), "A connection past the limit was kept");
        Assertions.assertEquals(1, limit.connections(), "The refused one took a slot with it");
        Assertions.assertEquals(1, limit.refused());

        kept.finishAndReleaseAll();
        refused.finishAndReleaseAll();
    }

    @Test
    public void andNothingBehindTheLimitIsToldAboutIt() {
        // there is nothing for them to do: the connection is already going
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(1);

        final Announced kept = new Announced();
        final Announced refusedAnnounced = new Announced();

        // the kept one is held open, or the slot it took would be free again
        final EmbeddedChannel first = new EmbeddedChannel(limit, kept);
        final EmbeddedChannel refused = new EmbeddedChannel(limit, refusedAnnounced);

        Assertions.assertTrue(kept.active, "The connection within the limit was not announced");
        Assertions.assertFalse(refusedAnnounced.active,
                "The handlers behind the limit saw a refused connection");

        first.finishAndReleaseAll();
        refused.finishAndReleaseAll();
    }

    @Test
    public void aSlotComesBackWhenTheConnectionGoes() {
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(1);

        final EmbeddedChannel first = connect(limit);
        first.close().syncUninterruptibly();

        Assertions.assertEquals(0, limit.connections());

        final EmbeddedChannel second = connect(limit);
        Assertions.assertTrue(second.isOpen(), "The slot the first one gave back was not reused");

        second.finishAndReleaseAll();
    }

    @Test
    public void aRefusedConnectionGivesBackNothingItNeverTook() {
        // the count is the whole point of the class: a decrement without a matching increment would let the
        // limit drift upwards, one refusal at a time, until it is not a limit at all
        final ConnectionLimitHandler limit = new ConnectionLimitHandler(1);

        final EmbeddedChannel kept = connect(limit);
        for (int i = 0; i < 8; i++) {
            connect(limit).finishAndReleaseAll();
        }

        Assertions.assertEquals(1, limit.connections());
        Assertions.assertEquals(8, limit.refused());

        kept.finishAndReleaseAll();
        Assertions.assertEquals(0, limit.connections());
    }

    @Test
    public void aServerWhichMayHoldNothingIsRefusedOutright() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ConnectionLimitHandler(0));
    }

    @Test
    public void theBuilderPutsItInFrontOfEverythingElse() throws Exception {
        try (NettyServer server = new NettyServerBuilder()
                .port(0)
                .host(HOST)
                .maxConnections(1)
                .pipeline(pipeline -> pipeline.addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx,
                                            final Object msg) {
                        ctx.writeAndFlush(msg); // an echo, so a test can tell that a connection is really up
                    }
                }))
                .start()) {

            final Socket first = new Socket(HOST, server.port());
            try {
                Assertions.assertEquals('x', echo(first),
                        "The connection which was within the limit was not served");

                try (Socket second = new Socket(HOST, server.port())) {
                    second.setSoTimeout(10_000);
                    Assertions.assertEquals(-1, second.getInputStream().read(),
                            "A connection past the limit was held rather than closed");
                }
            } finally {
                first.close();
            }

            // the server is not full any more, and the next peer to arrive is served
            try (Socket third = waitingForASlot(server.port())) {
                Assertions.assertEquals('x', echo(third), "The slot which was given back was not reused");
            }
        }
    }

    @Test
    public void andReportsWhatItRefusedToTheObserverTheBuilderWasGiven() throws Exception {
        // without a memory budget this is the only account of a refusal there is: nothing is written back,
        // so on the peer's side a full server and a dead one look the same
        final CountDownLatch refused = new CountDownLatch(1);

        try (NettyServer server = new NettyServerBuilder()
                .port(0)
                .host(HOST)
                .maxConnections(1)
                .connectionObserver(new ConnectionObserver() {
                    @Override
                    public void onConnectionRefused(final Channel channel) {
                        refused.countDown();
                    }
                })
                .pipeline(pipeline -> pipeline.addLast(new ChannelInboundHandlerAdapter()))
                .start()) {

            try (Socket first = new Socket(HOST, server.port());
                    Socket second = new Socket(HOST, server.port())) {
                second.setSoTimeout(10_000);
                Assertions.assertEquals(-1, second.getInputStream().read(),
                        "A connection past the limit was held rather than closed");
                Assertions.assertTrue(refused.await(10, TimeUnit.SECONDS),
                        "The refusal was never reported");
                Assertions.assertTrue(first.isConnected());
            }
        }
    }

    /**
     * Connects, and puts up with a refusal or two: the slot is given back on the event loop, some time
     * after the socket which held it was closed here.
     *
     * @param port of the server
     * @return a connection which was accepted and kept
     * @throws IOException if connecting does
     */
    private static Socket waitingForASlot(final int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            final Socket socket = new Socket(HOST, port);
            socket.setSoTimeout(10_000);
            try {
                if (echo(socket) == 'x') {
                    return socket;
                }
            } catch (final IOException closedOnArrival) {
                last = closedOnArrival;
            }
            socket.close();
            try {
                Thread.sleep(20);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last != null ? last : new IOException("The slot never came back");
    }

    private static int echo(final Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        final OutputStream out = socket.getOutputStream();
        out.write('x');
        out.flush();
        final InputStream in = socket.getInputStream();
        return in.read();
    }
}
