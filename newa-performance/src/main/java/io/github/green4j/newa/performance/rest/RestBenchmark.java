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
