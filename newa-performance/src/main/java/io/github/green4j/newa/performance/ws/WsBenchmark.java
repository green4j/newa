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
import io.github.green4j.newa.performance.ServerProcess;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;

/**
 * The WebSocket fan-out benchmark: every server, at every shape of run the sweep asks for, measured by the
 * same client.
 * <p>
 * A row is one server publishing into {@code channels} channels at {@code rate} messages a second each,
 * with {@code clients} subscribers, every one of them holding a single connection and taking every channel
 * on it. The frames such a row has to deliver are therefore {@code rate x channels x clients}, and the
 * answer the sweep gives is the highest rate at which everything still arrived, in order, with nobody
 * disconnected.
 * <p>
 * Each server is forked into its own JVM and killed again between rows, so no row inherits another's
 * warmed-up state, and the server's own statistics are read either side of the measured window rather than
 * around the warmup.
 */
public final class WsBenchmark {
    private static final String LOCALHOST = "127.0.0.1";

    private static final String DEFAULT_SERVERS =
            WsServerMain.NEWA + "," + SpringWsApplication.RAW + "," + SpringWsApplication.STOMP;

    private WsBenchmark() {
    }

    public static void main(final String[] args) throws Exception {
        final BenchmarkOptions options = new BenchmarkOptions();
        final Report report = new Report();

        final String[] servers = BenchmarkOptions.property("servers", DEFAULT_SERVERS).split(",");
        final int[] channelCounts = BenchmarkOptions.ints(BenchmarkOptions.property("channels", "1"));
        final long[] rates = BenchmarkOptions.longs(BenchmarkOptions.property("rate", "1000"));
        final int messageSize = Integer.parseInt(
                BenchmarkOptions.property("message", Integer.toString(WsPayload.DEFAULT_SIZE)));
        final int[] clientCounts = options.clients();

        for (int s = 0; s < servers.length; s++) {
            final String server = servers[s].trim();
            for (int h = 0; h < channelCounts.length; h++) {
                final int channels = channelCounts[h];
                for (int c = 0; c < clientCounts.length; c++) {
                    final int clients = clientCounts[c];
                    for (int r = 0; r < rates.length; r++) {
                        final long rate = rates[r];
                        System.out.printf("%n---- %s, %d channels, %d subscribers, %d msg/s "
                                        + "(%d frames/s offered) ----%n",
                                server, channels, clients, rate, rate * channels * clients);

                        try (ServerProcess process = ServerProcess.start(
                                WsServerMain.class.getName(),
                                server, options.port(), options.workers(), options.heap(),
                                "channels=" + channels,
                                "rate=" + rate,
                                "message=" + messageSize,
                                // the allowance is the server's to enforce, so the fork has to be told it
                                "lag=" + BenchmarkOptions.lagMillisProperty())) {

                            final JvmStats before = process.statsOrUnavailable();
                            final LoadResult result = WsClientMain.measure(
                                    options, LOCALHOST, options.port(), clients, channels, server);
                            final JvmStats after = process.statsOrUnavailable();

                            report.addFanout(server, channels, rate, result, after.since(before));
                        }
                    }
                }
            }
        }

        report.printFanout(System.out, options);
    }
}
