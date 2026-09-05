/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.LoadClient;
import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.Report;

/**
 * The load client against a server which is already running - started by the {@code restServer} task, by
 * hand, or on another host. {@code RestBenchmark} uses {@link #measure} for the servers it forks itself.
 */
public final class RestClientMain {
    private RestClientMain() {
    }

    /**
     * Warms up, measures, and reports what the client itself cost while doing it.
     *
     * @param options the run was shaped by
     * @param host    to load
     * @param port    to load
     * @param clients connections to carry
     * @return what the measured window produced
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public static LoadResult measure(final BenchmarkOptions options,
                                     final String host,
                                     final int port,
                                     final int clients) throws InterruptedException {
        try (LoadClient client = new LoadClient(
                options.mode(), host, port, clients, options.rate(), RestPayload.PATH_PREFIX)) {

            client.startAndWarmUp(options.warmupSeconds());

            final JvmStats before = JvmStats.current();
            final LoadResult result = client.measure(options.durationSeconds());
            final JvmStats after = JvmStats.current();

            return result.withClientCpu(after.since(before).processCpuNanos());
        }
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

        final Report report = new Report();
        final int[] clientCounts = options.clients();
        for (int c = 0; c < clientCounts.length; c++) {
            final int clients = clientCounts[c];
            System.out.printf("%n---- %s, %d clients ----%n", target, clients);

            final JvmStats before = JvmStats.fetch(host, port);
            final LoadResult result = measure(options, host, port, clients);
            final JvmStats after = JvmStats.fetch(host, port);

            report.add(target, result, after.since(before));
        }
        report.print(System.out, options);
    }
}
