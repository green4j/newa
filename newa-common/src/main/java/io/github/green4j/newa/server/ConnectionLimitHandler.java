/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closes a connection which would take a server past the number of them it is willing to hold.
 * <p>
 * What it bounds is file descriptors. {@link IdleConnectionHandler} takes one back from a connection nobody
 * is using, which is the common case and not the dangerous one: connections which are all busy at once cost
 * a descriptor each until they are done, and a process out of descriptors stops accepting anything at all -
 * including the connection which would have said what is wrong. The number belongs to the deployment rather
 * than to this library, which is why there is no default.
 * <p>
 * One instance is shared by every channel of one server - the count is what it holds, and there is one of
 * those - so it is {@code @Sharable} and counts atomically. {@link NettyServerBuilder#maxConnections(int)}
 * builds one; a pipeline written by hand builds its own once, outside the {@code ChannelInitializer} which
 * adds it. It belongs at the head, in front of any codec: nothing further in has anything to do until the
 * connection is known to be one this server is keeping.
 * <p>
 * <b>A refused connection is closed without a word</b>, and that is arithmetic rather than politeness:
 * writing a {@code 503} means holding the descriptor through a write and a flush, which is the resource
 * being defended, at the moment there are none to spare. It would not arrive reliably either - the response
 * would be written before the request had been read, and closing with unread bytes in the receive buffer
 * sends an RST which takes it with it. That answer is given where a server knows what it is answering:
 * {@code newa-rest} refuses a request it has read with a {@code 503} of its own.
 * <p>
 * The cost of saying nothing is that a refusal looks, on the client's side, like a server which died.
 * {@link #refused()} is the other side of that: overload is a number this server can read about itself.
 */
@ChannelHandler.Sharable
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong refused = new AtomicLong();

    private final int maxConnections;

    /**
     * @param maxConnections this server will hold at once, above which one is closed as it arrives.
     */
    public ConnectionLimitHandler(final int maxConnections) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("A server which may hold no connection would serve nothing: "
                    + maxConnections);
        }
        this.maxConnections = maxConnections;
    }

    /**
     * @return how many connections are open right now.
     */
    public int connections() {
        return connections.get();
    }

    /**
     * @return how many have been closed on arrival because the server was full.
     */
    public long refused() {
        return refused.get();
    }

    /**
     * @return the number of connections above which one is refused.
     */
    public int maxConnections() {
        return maxConnections;
    }

    // the slot is given back by a listener on the close future rather than by channelInactive, and the
    // listener is added only once the slot has been taken: a channel which never got one must not give one
    // back, and one closed before it was ever active must not be counted at all
    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        if (connections.incrementAndGet() > maxConnections) {
            connections.decrementAndGet();
            refused.incrementAndGet();
            ctx.close();
            return; // nothing behind this is told about a connection which is already going
        }
        ctx.channel().closeFuture().addListener(closed -> connections.decrementAndGet());
        ctx.fireChannelActive();
    }
}
