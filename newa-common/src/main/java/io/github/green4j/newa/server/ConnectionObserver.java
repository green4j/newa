/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.Channel;

/**
 * The connections a server refuses or closes by a rule of its own. All of them are silent on the wire, so
 * without this they are indistinguishable, on both sides, from a peer which went away.
 * <p>
 * One instance serves a whole server, so it is called from every event loop at once and must not block.
 * Every call happens before the close, while the channel still knows its peer. An exception leaving one is
 * swallowed.
 */
public interface ConnectionObserver {
    /**
     * @param channel closed by {@link ConnectionLimitHandler}. The same refusal under a
     *                {@link ServerMemoryBudget} goes to that budget's observer instead
     */
    default void onConnectionRefused(final Channel channel) {
    }

    /**
     * @param channel closed by {@link IdleConnectionHandler}
     */
    default void onIdleTimeout(final Channel channel) {
    }

    /**
     * @param channel closed by {@link RequestDeadlineHandler}. The request never arrived, so no observer of
     *                requests can report it
     */
    default void onRequestDeadlineExpired(final Channel channel) {
    }

    /**
     * @param channel closed by {@link ResponseDeadlineHandler}
     */
    default void onResponseStalled(final Channel channel) {
    }

    /**
     * @param channel closed by {@link SingleHttpExchangeHandler} for pipelining deeper than one request
     */
    default void onPipelinedRequestRefused(final Channel channel) {
    }
}
