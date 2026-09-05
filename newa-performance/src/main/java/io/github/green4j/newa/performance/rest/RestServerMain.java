/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.Cores;
import io.github.green4j.newa.performance.rest.newa.NewaRestServer;
import io.github.green4j.newa.performance.rest.spring.SpringRestServer;

import java.util.concurrent.CountDownLatch;

/**
 * Starts one of the servers under test and leaves it running.
 * <p>
 * This is what {@code ServerProcess} forks, and it is also the {@code restServer} Gradle task, so a server
 * profiled by hand is the same process the benchmark measures.
 */
public final class RestServerMain {
    public static final String NEWA = "newa";
    public static final String SPRING = "spring";

    private RestServerMain() {
    }

    /**
     * @param server  {@link #NEWA} or {@link #SPRING}
     * @param port    to listen on, or 0 for an ephemeral one
     * @param workers event loops for the newa server. The Spring server keeps Boot's defaults and ignores it
     * @return the running server
     * @throws InterruptedException if the calling thread is interrupted while binding
     */
    public static RestServer start(final String server,
                                   final int port,
                                   final int workers) throws InterruptedException {
        if (NEWA.equalsIgnoreCase(server)) {
            return NewaRestServer.start(port, workers);
        }
        if (SPRING.equalsIgnoreCase(server)) {
            return SpringRestServer.start(port);
        }
        throw new IllegalArgumentException("Unknown server: " + server
                + ". Expected '" + NEWA + "' or '" + SPRING + "'");
    }

    public static void main(final String[] args) throws Exception {
        final String server = BenchmarkOptions.property("server", NEWA);
        final int port = Integer.parseInt(BenchmarkOptions.property("port", "9100"));
        final int workers = Integer.parseInt(
                BenchmarkOptions.property("workers", Integer.toString(Cores.serverThreads())));

        final RestServer running = start(server, port, workers);
        System.out.printf("%s listening on http://127.0.0.1:%d%s, workers=%d%n",
                server, running.port(), RestPayload.PATH_PREFIX, workers);

        final CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.close();
            stopped.countDown();
        }));
        stopped.await();
    }
}
