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


package io.github.green4j.newa.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

class IdleConnectionHandlerTest {
    private static final int TIMEOUT_MS = 1000;
    private static final long STEP_MS = 50;

    private static EmbeddedChannel channelWithTheHandler() {
        return new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_MS));
    }

    private static void letTimePass(final EmbeddedChannel channel,
                                    final long millis) {
        // an EmbeddedChannel runs a scheduled task only when it is told to, which is what makes this
        // deterministic: no sleeping, and no timer to be flaky about. It is moved in steps because a task
        // which reschedules itself does not run twice in one pass, and this handler needs two expiries
        for (long passed = 0; passed < millis; passed += STEP_MS) {
            channel.advanceTimeBy(STEP_MS, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
    }

    private static void write(final EmbeddedChannel channel) {
        channel.writeOutbound(Unpooled.copiedBuffer("out", StandardCharsets.US_ASCII));
    }

    private static void read(final EmbeddedChannel channel) {
        channel.writeInbound(Unpooled.copiedBuffer("in", StandardCharsets.US_ASCII));
        channel.releaseInbound();
    }

    @Test
    public void aConnectionWhichSaysNothingIsClosed() {
        final EmbeddedChannel channel = channelWithTheHandler();

        letTimePass(channel, TIMEOUT_MS);

        Assertions.assertFalse(channel.isOpen(), "the connection was left open");
    }

    @Test
    public void itIsClosedAtTheTimeoutItWasGiven() {
        // the halving inside is an implementation detail of how progress is observed, and it is not
        // allowed to move when the connection actually goes
        final EmbeddedChannel channel = channelWithTheHandler();

        letTimePass(channel, TIMEOUT_MS * 3 / 4);
        Assertions.assertTrue(channel.isOpen(), "closed at less than the timeout it was given");

        letTimePass(channel, TIMEOUT_MS / 2);
        Assertions.assertFalse(channel.isOpen(), "held past the timeout it was given");

        channel.finishAndReleaseAll();
    }

    @Test
    public void aConnectionWhichIsReadFromIsNot() {
        final EmbeddedChannel channel = channelWithTheHandler();

        for (int i = 0; i < 4; i++) {
            letTimePass(channel, TIMEOUT_MS * 3 / 4);
            read(channel);
        }

        Assertions.assertTrue(channel.isOpen(), "a connection in use was closed");

        letTimePass(channel, TIMEOUT_MS * 2);
        Assertions.assertFalse(channel.isOpen(), "and then it stopped and was not closed");

        channel.finishAndReleaseAll();
    }

    @Test
    public void aConnectionWhichIsWrittenToIsNot() {
        // this is what makes it safe in front of a long response: a chunked one still being written keeps
        // its own connection alive, however silent the peer receiving it is
        final EmbeddedChannel channel = channelWithTheHandler();

        for (int i = 0; i < 4; i++) {
            letTimePass(channel, TIMEOUT_MS * 3 / 4);
            write(channel);
        }

        Assertions.assertTrue(channel.isOpen(), "a connection being written to was closed");

        letTimePass(channel, TIMEOUT_MS * 2);
        Assertions.assertFalse(channel.isOpen(), "and then it stopped and was not closed");

        channel.finishAndReleaseAll();
    }

    @Test
    public void nettysOwnHandlerWouldHaveClosedNothing() {
        // the reason this class exists: IdleStateHandler fires an event and leaves the deciding to whatever
        // is behind it, and a pipeline with nothing for that event holds the connection either way
        final EmbeddedChannel channel = new EmbeddedChannel(
                new io.netty.handler.timeout.IdleStateHandler(0, 0, TIMEOUT_MS, TimeUnit.MILLISECONDS));

        letTimePass(channel, TIMEOUT_MS * 2);

        Assertions.assertTrue(channel.isOpen());

        channel.finishAndReleaseAll();
    }
}
