/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
