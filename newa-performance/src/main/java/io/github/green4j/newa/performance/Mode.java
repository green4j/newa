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

package io.github.green4j.newa.performance;

/**
 * What a run measures. Never both at once: the client which saturates a server cannot also tell you what its
 * latency is at a given load, and the client which offers a fixed load is not measuring how much it could
 * have offered.
 */
public enum Mode {
    /**
     * Closed loop. Every connection sends its next request the moment the previous response arrives, so the
     * offered load is whatever the server can take, and what is reported is how much that was.
     */
    THROUGHPUT,

    /**
     * Open loop at a fixed offered rate. Requests are issued on a schedule which does not wait for the
     * server, and each one is timed from the instant it was due rather than the instant it went out.
     */
    LATENCY;

    public static Mode parse(final String value) {
        for (final Mode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + value
                + ". Expected 'throughput' or 'latency'");
    }
}
