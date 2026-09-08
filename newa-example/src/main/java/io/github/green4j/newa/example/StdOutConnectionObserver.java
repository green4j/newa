/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example;

import io.github.green4j.newa.server.ConnectionObserver;
import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Prints every connection this server refuses or closes on its own, with a running count of each kind.
 * <p>
 * One of these serves a whole server - unlike the request and session observers here, which are made per
 * request and per session - so it keeps nothing about any one connection and counts atomically.
 */
public class StdOutConnectionObserver implements ConnectionObserver {
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong idle = new AtomicLong();
    private final AtomicLong requestDeadlines = new AtomicLong();
    private final AtomicLong stalled = new AtomicLong();
    private final AtomicLong pipelined = new AtomicLong();

    @Override
    public void onConnectionRefused(final Channel channel) {
        print("A connection refused, the server is full", channel, refused);
    }

    @Override
    public void onIdleTimeout(final Channel channel) {
        print("A connection idle for longer than the timeout closed", channel, idle);
    }

    @Override
    public void onRequestDeadlineExpired(final Channel channel) {
        // the peer no idle timeout catches: it was sending all along, just never finished
        print("A request begun and not finished in time", channel, requestDeadlines);
    }

    @Override
    public void onResponseStalled(final Channel channel) {
        print("A peer stopped taking its response", channel, stalled);
    }

    @Override
    public void onPipelinedRequestRefused(final Channel channel) {
        print("A request pipelined deeper than this server serves", channel, pipelined);
    }

    private static void print(final String what,
                              final Channel channel,
                              final AtomicLong count) {
        // still open here, so it still knows its peer: a moment later the address would be null
        System.out.printf("%s: %s (%d so far)%n", what, channel.remoteAddress(), count.incrementAndGet());
    }
}
