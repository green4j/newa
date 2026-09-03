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

package io.github.green4j.newa.lang;

import io.netty.channel.Channel;

import java.io.IOException;

/**
 * Ignores a peer which went away and prints everything else to stderr. A connection reset or a half closed
 * socket is what the end of every client looks like, and reporting it says nothing about this server.
 * <p>
 * A cause with a stack trace gets it, under a line naming the channel - a failure is worth all of it. A
 * cause without one is the whole report on a single line, type and message included: an exception raised to
 * say what a peer did - a request which was not the handshake, say - has no frames worth naming, and its
 * header alone would only repeat what the line already says.
 * <p>
 * What the server helpers use when none was given. Pass one of your own to reach a logger.
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
