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

package io.github.green4j.newa.performance.rest;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.Report;
import io.github.green4j.newa.performance.ServerProcess;

/**
 * The REST benchmark: every server, at every client count the run asks for, measured by the same client.
 * <p>
 * Each server is forked into its own JVM and killed again between runs, so no run inherits another's
 * warmed-up state, and the server's own statistics are read either side of the measured window rather than
 * around the warmup.
 */
public final class RestBenchmark {
    private static final String LOCALHOST = "127.0.0.1";

    private static final String DEFAULT_SERVERS = RestServerMain.NEWA + "," + RestServerMain.SPRING;

    private RestBenchmark() {
    }

    public static void main(final String[] args) throws Exception {
        final BenchmarkOptions options = new BenchmarkOptions();
        final Report report = new Report();

        final String[] servers = BenchmarkOptions.property("servers", DEFAULT_SERVERS).split(",");
        for (int s = 0; s < servers.length; s++) {
            final String server = servers[s].trim();
            final int[] clientCounts = options.clients();
            for (int c = 0; c < clientCounts.length; c++) {
                final int clients = clientCounts[c];
                System.out.printf("%n---- %s, %d clients ----%n", server, clients);

                try (ServerProcess process = ServerProcess.start(
                        RestServerMain.class.getName(),
                        server, options.port(), options.workers(), options.heap())) {

                    final JvmStats before = process.statsOrUnavailable();
                    final LoadResult result =
                            RestClientMain.measure(options, LOCALHOST, options.port(), clients);
                    final JvmStats after = process.statsOrUnavailable();

                    report.add(server, result, after.since(before));
                }
            }
        }

        report.print(System.out, options);
    }
}
