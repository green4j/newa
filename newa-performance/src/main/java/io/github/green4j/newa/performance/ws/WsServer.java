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

package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.BenchmarkOptions;

/**
 * A server under test. All three implementations accept subscriptions on the same path, publish the same
 * messages into the same channels, answer the same statistics endpoint, and stop.
 */
public interface WsServer extends AutoCloseable {
    /**
     * How many times the service level a server may hold for one subscriber before giving up on it. It is a
     * valve against unbounded queueing and not a verdict: a row is judged by what the client measured
     * against {@link BenchmarkOptions#lagMillis()}, so that the answer does not depend on what each server
     * does with a subscriber it cannot serve.
     */
    int SAFETY_FACTOR = 10;

    /**
     * The fewest messages the valve is ever worth, whatever the arithmetic says.
     */
    int MIN_BUDGET_MESSAGES = 8;

    /**
     * The valve in bytes, derived from the rate a subscriber is sent at so that one run parameter means the
     * same thing to every server and in every shape of run.
     *
     * @param channels    a subscriber takes, all of them on its one connection
     * @param rate        each channel publishes at, in messages per second
     * @param messageSize bytes a published message is
     * @return bytes one subscriber may be behind by before the server disconnects it
     */
    static int outboundBudgetBytes(final int channels,
                                   final long rate,
                                   final int messageSize) {
        final long perSecond = rate * channels * messageSize;
        final long budget = perSecond * SAFETY_FACTOR * BenchmarkOptions.lagMillisProperty() / 1000L;
        return (int) Math.max(budget, (long) MIN_BUDGET_MESSAGES * messageSize);
    }

    /**
     * @return the port actually bound, which is what a caller who asked for port 0 needs
     */
    int port();

    /**
     * Publishes one message into one channel. Called from that channel's publisher thread and no other, so
     * an implementation neither needs nor should have any synchronization of its own here.
     *
     * @param channel index to publish into
     */
    void publish(int channel);

    @Override
    void close();
}
