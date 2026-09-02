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
 * How the machine is divided between the load client and the server under test.
 * <p>
 * A benchmark run puts both on one host, so the only way the numbers mean anything is for the split to be
 * fixed and stated: the client takes half the cores and no more, the server is given the rest. Neither half
 * is a knob - a run in which the client had taken more would not be comparable with any other run.
 */
public final class Cores {
    private Cores() {
    }

    /**
     * @return number of cores this JVM sees
     */
    public static int available() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * @return I/O threads the load client runs on: half the machine, rounded down, but never zero
     */
    public static int clientThreads() {
        return Math.max(1, available() / 2);
    }

    /**
     * @return threads the server is given: whatever the client did not take
     */
    public static int serverThreads() {
        return Math.max(1, available() - clientThreads());
    }
}
