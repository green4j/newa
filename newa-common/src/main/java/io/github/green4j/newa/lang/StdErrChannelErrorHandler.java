/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import io.netty.channel.Channel;

import java.io.IOException;

/**
 * Prints to stderr what is worth printing. A peer which went away is ignored - a connection reset is what
 * the end of every client looks like and says nothing about this server - and everything else is named with
 * its channel.
 * <p>
 * A cause carrying a stack trace gets all of it. One without frames is reported on a single line, type and
 * message included: an exception raised to say what a peer did has no stack worth naming.
 * <p>
 * What the server helpers use until they are given one. Pass a handler of your own to reach a logger.
 */
public class StdErrChannelErrorHandler implements ChannelErrorHandler {

    @Override
    public void onError(final Channel channel,
                        final Throwable cause) {
        if (cause instanceof IOException) {
            return;
        }
        if (cause.getStackTrace().length == 0) {
            System.err.printf("Channel %s failed: %s%n", channel, cause);
            return;
        }
        System.err.printf("Channel %s failed:%n", channel);
        cause.printStackTrace(System.err);
    }
}
