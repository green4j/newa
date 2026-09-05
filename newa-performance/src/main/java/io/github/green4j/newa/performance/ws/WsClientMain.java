/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.Report;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;

/**
 * The load client against a server which is already running - started by the {@code wsServer} task, by hand,
 * or on another host. {@code WsBenchmark} uses {@link #measure} for the servers it forks itself.
 */
public final class WsClientMain {
    private WsClientMain() {
    }

    /**
     * Subscribes, warms up, measures, and reports what the client itself cost while doing it.
     *
     * @param options  the run was shaped by
     * @param host     to subscribe to
     * @param port     to subscribe to
     * @param clients  subscribers to carry, each taking every channel on one connection
     * @param channels the server is publishing into
     * @param server   name of the server, which decides whether the subscription is STOMP or plain text
     * @return what the measured window produced
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public static LoadResult measure(final BenchmarkOptions options,
                                     final String host,
                                     final int port,
                                     final int clients,
                                     final int channels,
                                     final String server) throws InterruptedException {
        try (WsLoadClient client = new WsLoadClient(host, port, clients, channels, isStomp(server))) {
            client.startAndWarmUp(options.warmupSeconds());

            final JvmStats before = JvmStats.current();
            final LoadResult result = client.measure(options.durationSeconds());
            final JvmStats after = JvmStats.current();

            return result.withClientCpu(after.since(before).processCpuNanos());
        }
    }

    /**
     * @param server name of the server under test
     * @return whether a subscription to it is a STOMP frame rather than a line of text
     */
    public static boolean isStomp(final String server) {
        return SpringWsApplication.STOMP.equalsIgnoreCase(server);
    }

    public static void main(final String[] args) throws Exception {
        final BenchmarkOptions options = new BenchmarkOptions();

        final String target = BenchmarkOptions.property("target", "127.0.0.1:9100");
        final int colon = target.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("target must be host:port, got " + target);
        }
        final String host = target.substring(0, colon);
        final int port = Integer.parseInt(target.substring(colon + 1));

        final String server = BenchmarkOptions.property("server", WsServerMain.NEWA);
        final int channels = Integer.parseInt(BenchmarkOptions.property("channels", "1"));
        final long rate = BenchmarkOptions.longs(BenchmarkOptions.property("rate", "1000"))[0];

        final Report report = new Report();
        final int[] clientCounts = options.clients();
        for (int c = 0; c < clientCounts.length; c++) {
            final int clients = clientCounts[c];
            System.out.printf("%n---- %s, %d channels, %d subscribers ----%n", target, channels, clients);

            final JvmStats before = JvmStats.fetch(host, port);
            final LoadResult result = measure(options, host, port, clients, channels, server);
            final JvmStats after = JvmStats.fetch(host, port);

            report.addFanout(target, channels, rate, result, after.since(before));
        }
        report.printFanout(System.out, options);
    }
}
