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

import org.HdrHistogram.Histogram;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The table a run prints. One row per server per client count, in the order the runs happened.
 */
public final class Report {
    private static final double NANOS_PER_MICRO = 1000.0;
    private static final double MICROS_PER_MILLI = 1000.0;

    /**
     * How close to the offered rate a row has to have got. A publisher keeping an open loop schedule ends a
     * window a publication or two either side of it, which is not a server falling behind.
     */
    private static final double BEHIND_THRESHOLD = 0.99;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static final String THROUGHPUT_HEADER =
            "%-8s %8s %12s %12s %10s %9s %9s %6s %6s %12s%n";
    private static final String THROUGHPUT_ROW =
            "%-8s %8d %12.0f %12s %10.1f %9s %9s %6s %6s %12s%n";

    private static final String LATENCY_HEADER =
            "%-8s %8s %10s %10s %9s %9s %9s %9s %9s %9s %9s %9s %9s%n";
    private static final String LATENCY_ROW =
            "%-8s %8d %10d %10.0f %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f %9s %9s %9d%n";

    private static final String FANOUT_HEADER =
            "%-12s %3s %8s %10s %11s %12s %14s %9s %9s %9s %10s%n";
    private static final String FANOUT_ROW =
            "%-12s %3d %8d %10d %11.0f %12.0f %14s %9.1f %9.1f %9.1f %10s%n";

    private static final String COST_HEADER = "%-12s %3s %8s %10s %10s %6s %6s %14s%n";
    private static final String COST_ROW = "%-12s %3d %8d %10s %10s %6s %6s %14s%n";

    private final List<Row> rows = new ArrayList<>();

    /**
     * @param name   of the server this run went against, as the table is to show it
     * @param result of one run, carrying what the load client itself cost
     * @param server what the server reported for the same window
     */
    public void add(final String name,
                    final LoadResult result,
                    final JvmStats server) {
        rows.add(new Row(name, 0, 0L, result, server));
    }

    /**
     * Adds one row of a fan-out run, which is shaped by two things a request/response run has no notion of:
     * how many channels were publishing, and how fast each of them was told to.
     *
     * @param name     of the server this run went against, as the table is to show it
     * @param channels published into, each by a thread of its own
     * @param offeredRate each channel was asked to publish at, in messages per second
     * @param result   of one run, carrying what the load client itself cost
     * @param server   what the server reported for the same window
     */
    public void addFanout(final String name,
                          final int channels,
                          final long offeredRate,
                          final LoadResult result,
                          final JvmStats server) {
        rows.add(new Row(name, channels, offeredRate, result, server));
    }

    /**
     * @param out     to print to
     * @param options the run was shaped by
     */
    public void print(final PrintStream out,
                      final BenchmarkOptions options) {
        out.println();
        out.printf("mode=%s  transport=%s  cores=%d  clientThreads=%d  newaWorkers=%d  "
                        + "warmup=%ds  duration=%ds%n",
                options.mode().name().toLowerCase(Locale.ROOT),
                Transport.name(),
                Cores.available(),
                Cores.clientThreads(),
                options.workers(),
                options.warmupSeconds(),
                options.durationSeconds());
        if (options.mode() == Mode.LATENCY) {
            out.printf("offered rate=%d req/s%n", options.rate());
        }
        out.println();

        if (options.mode() == Mode.THROUGHPUT) {
            printThroughput(out);
        } else {
            printLatency(out, options.rate());
        }
        printTrouble(out);
        printRatios(out, options.mode(), options.rate());
        out.println();
        out.println("req/core-s = requests answered per second of server processor time. The one number");
        out.println("            which survives the two servers being allowed different amounts of machine:");
        out.println("            newa is held to its workers, Tomcat's pool is not.");
        out.println("srv/cli cores = processor time the two JVMs used, divided by the window. A side which");
        out.println("                reaches the threads it was given has run out of machine; a side well");
        out.println("                below it is waiting for the other one.");
        if (options.mode() == Mode.LATENCY) {
            out.println("backlog = requests which were due with no free connection to go out on. Anything");
            out.println("          much above zero means the offered rate was past what the server can take,");
            out.println("          and the percentiles describe an overloaded server rather than a loaded one.");
        }
    }

    /**
     * The fan-out table. A run here is not a rate the client extracted but a rate the server was told to
     * publish at, so the columns say three different things at once: whether the publishers kept the
     * schedule, whether everything they published arrived, and what it cost to deliver.
     *
     * @param out     to print to
     * @param options the run was shaped by
     */
    public void printFanout(final PrintStream out,
                            final BenchmarkOptions options) {
        out.println();
        out.printf("fan-out  transport=%s  cores=%d  clientThreads=%d  newaWorkers=%d  "
                        + "warmup=%ds  duration=%ds%n",
                Transport.name(),
                Cores.available(),
                Cores.clientThreads(),
                options.workers(),
                options.warmupSeconds(),
                options.durationSeconds());
        out.println("Every subscriber holds one connection and subscribes to every channel on it, so the");
        out.println("frames a run delivers are rate x channels x subscribers.");
        out.println();

        out.printf(FANOUT_HEADER,
                "server", "ch", "clients", "offered/s", "achieved/s", "frames/s", "frames/core-s",
                "p50 us", "p99 us", "max us", "verdict");
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final Histogram latencies = row.result.latencies();
            out.printf(FANOUT_ROW,
                    row.name,
                    row.channels,
                    row.result.clients(),
                    row.offeredRate,
                    row.achievedRate(),
                    row.result.requestsPerSecond(),
                    row.requestsPerCoreSecondText(),
                    micros(latencies, 50.0),
                    micros(latencies, 99.0),
                    latencies.getMaxValue() / NANOS_PER_MICRO,
                    row.verdict(options.lagMillis()));
        }
        printWhatWentWrong(out, options.lagMillis());

        out.println();
        out.printf(COST_HEADER,
                "server", "ch", "clients", "srv cores", "cli cores", "gc", "gcMs", "alloc B/frame");
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            out.printf(COST_ROW,
                    row.name,
                    row.channels,
                    row.result.clients(),
                    row.cores(row.server.processCpuNanos()),
                    row.cores(row.result.clientCpuNanos()),
                    row.collections(),
                    row.collectionMillis(),
                    row.allocationPerRequest());
        }

        printFanoutRatios(out);

        out.println();
        out.printf("verdict = whether the row was served, against a %d ms service level (-Plag): ok, or the%n",
                options.lagMillis());
        out.println("            first of lag (p99 past it), behind (the stream fell short of the offered");
        out.println("            rate), holes, shuffled, dropped. What each failure was is printed above.");
        out.println("The verdict is measured rather than taken from the server, so it does not depend on what");
        out.println("            a server does with a subscriber it cannot serve - disconnect it, as newa");
        out.println("            does, or stall the thread writing to it, as a blocking send does. Either");
        out.println("            shows up here, as dropped or as lag.");
        out.println("frames/core-s = frames delivered per second of server processor time - the column to");
        out.println("            compare on, because the two servers are not held to the same threads.");
        out.println("Latency is one way, from the instant of publication carried in the message itself.");
    }

    /**
     * What was behind every verdict which was not {@code ok}. These have no columns of their own because in a
     * run worth reading they are all zero.
     *
     * @param out       to print to
     * @param lagMillis a subscriber may be behind by and still count as served
     */
    private void printWhatWentWrong(final PrintStream out,
                                    final int lagMillis) {
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            if (Row.OK.equals(row.verdict(lagMillis))) {
                continue;
            }
            out.printf("%n%s at %d subscribers and %d msg/s: p99 %.1f ms against a %d ms service level, "
                            + "%.0f of %d msg/s delivered, %d subscribers dropped, the sequence jumped %d "
                            + "times, %d messages arrived after one which came later%n",
                    row.name, row.result.clients(), row.offeredRate,
                    row.p99Micros() / 1000.0, lagMillis,
                    row.achievedRate(), row.offeredRate,
                    row.result.ioErrors(), row.result.gaps(), row.result.reordered());
        }
    }

    /**
     * How many times more frames the first server delivered per core than each of the others, per shape of
     * the run. The raw rates belong to the machine; this ratio is what carries over.
     *
     * @param out to print to
     */
    private void printFanoutRatios(final PrintStream out) {
        final List<String> servers = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!servers.contains(rows.get(i).name)) {
                servers.add(rows.get(i).name);
            }
        }
        if (servers.size() < 2) {
            return; // nothing to compare against
        }

        final String baseline = servers.get(0);
        for (int s = 1; s < servers.size(); s++) {
            final String other = servers.get(s);
            out.println();
            out.printf("%s against %s, frames delivered per core of server processor time:%n",
                    baseline, other);
            out.printf("%3s %8s %10s %14s%n", "ch", "clients", "offered/s", "frames/core-s");
            for (int i = 0; i < rows.size(); i++) {
                final Row base = rows.get(i);
                if (!base.name.equals(baseline)) {
                    continue;
                }
                final Row against = findFanout(other, base);
                if (against == null) {
                    continue;
                }
                out.printf("%3d %8d %10d %14s%n",
                        base.channels,
                        base.result.clients(),
                        base.offeredRate,
                        ratio(base.requestsPerCoreSecond(), against.requestsPerCoreSecond()));
            }
        }
    }

    private Row findFanout(final String server,
                           final Row like) {
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            if (row.name.equals(server)
                    && row.channels == like.channels
                    && row.offeredRate == like.offeredRate
                    && row.result.clients() == like.result.clients()) {
                return row;
            }
        }
        return null;
    }

    /**
     * Whatever went wrong, and nothing when nothing did. These have no column of their own because in a run
     * worth reading they are all zero, and a column of zeroes only makes the table harder to see - but a run
     * in which they are not zero is not a result, so it has to say so.
     *
     * @param out to print to
     */
    private void printTrouble(final PrintStream out) {
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final long bad = row.result.badStatuses();
            final long io = row.result.ioErrors();
            final long reconnects = row.result.reconnects();
            if (bad == 0 && io == 0 && reconnects == 0) {
                continue;
            }
            out.printf("%n%s at %d clients: %d responses other than 200 (not counted as requests), "
                            + "%d connections failed with a request outstanding, "
                            + "%d re-established after the server closed them%n",
                    row.name, row.result.clients(), bad, io, reconnects);
        }
    }

    /**
     * How many times more work the first server got out of a core than each of the others, per client count.
     * The raw rates belong to the machine the run happened on; this ratio is what carries over.
     *
     * @param out  to print to
     * @param mode the run was in
     * @param rate offered, in requests per second, in {@link Mode#LATENCY}
     */
    private void printRatios(final PrintStream out,
                             final Mode mode,
                             final long rate) {
        final List<String> servers = new ArrayList<>();
        final List<Integer> clientCounts = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            if (!servers.contains(row.name)) {
                servers.add(row.name);
            }
            if (!clientCounts.contains(row.result.clients())) {
                clientCounts.add(row.result.clients());
            }
        }
        if (servers.size() < 2) {
            return; // nothing to compare against
        }

        final String baseline = servers.get(0);
        for (int s = 1; s < servers.size(); s++) {
            final String other = servers.get(s);
            out.println();
            out.printf("%s against %s, per core of server processor time%s:%n",
                    baseline, other, mode == Mode.LATENCY ? " and at the 99th percentile" : "");
            if (mode == Mode.LATENCY) {
                out.printf("%8s %14s %14s%n", "clients", "req/core-s", "p99");
            } else {
                out.printf("%8s %14s%n", "clients", "req/core-s");
            }
            boolean anyBehind = false;
            for (int c = 0; c < clientCounts.size(); c++) {
                final int clients = clientCounts.get(c);
                final Row base = find(baseline, clients);
                final Row against = find(other, clients);
                if (base == null || against == null) {
                    continue;
                }
                if (mode == Mode.LATENCY) {
                    final boolean behind = base.fellBehind(rate) || against.fellBehind(rate);
                    out.printf("%8d %14s %14s%n",
                            clients,
                            ratio(base.requestsPerCoreSecond(), against.requestsPerCoreSecond()),
                            // lower is better, so the ratio is the other way round
                            ratio(against.p99Micros(), base.p99Micros()) + (behind ? " *" : ""));
                } else {
                    out.printf("%8d %14s%n",
                            clients,
                            ratio(base.requestsPerCoreSecond(), against.requestsPerCoreSecond()));
                }
                anyBehind |= mode == Mode.LATENCY && (base.fellBehind(rate) || against.fellBehind(rate));
            }
            if (anyBehind) {
                out.println("  * one of the two ended the run in arrears at this rate. Its percentiles are");
                out.println("    still true - they are timed from when each request was due - but a server");
                out.println("    past capacity has a tail which grows with the length of the run, so the");
                out.println("    figure belongs to this run rather than to the server.");
            }
        }
    }

    private Row find(final String server,
                     final int clients) {
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            if (row.name.equals(server) && row.result.clients() == clients) {
                return row;
            }
        }
        return null;
    }

    private static String ratio(final double value,
                                final double against) {
        if (Double.isNaN(value) || Double.isNaN(against) || against == 0.0) {
            return "n/a";
        }
        return String.format("%.2fx", value / against);
    }

    private void printThroughput(final PrintStream out) {
        out.printf(THROUGHPUT_HEADER,
                "server", "clients", "req/s", "req/core-s", "MB/s", "srv cores", "cli cores",
                "gc", "gcMs", "alloc B/req");
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            out.printf(THROUGHPUT_ROW,
                    row.name,
                    row.result.clients(),
                    row.result.requestsPerSecond(),
                    row.requestsPerCoreSecondText(),
                    row.result.megabytesPerSecond(),
                    row.cores(row.server.processCpuNanos()),
                    row.cores(row.result.clientCpuNanos()),
                    row.collections(),
                    row.collectionMillis(),
                    row.allocationPerRequest());
        }
    }

    private void printLatency(final PrintStream out,
                              final long offeredRate) {
        out.printf(LATENCY_HEADER,
                "server", "clients", "offered/s", "actual/s", "p50 us", "p90 us", "p99 us",
                "p99.9 us", "p99.99us", "max us", "srv cores", "cli cores", "backlog");
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final Histogram latencies = row.result.latencies();
            out.printf(LATENCY_ROW,
                    row.name,
                    row.result.clients(),
                    offeredRate,
                    row.result.requestsPerSecond(),
                    micros(latencies, 50.0),
                    micros(latencies, 90.0),
                    micros(latencies, 99.0),
                    micros(latencies, 99.9),
                    micros(latencies, 99.99),
                    latencies.getMaxValue() / NANOS_PER_MICRO,
                    row.cores(row.server.processCpuNanos()),
                    row.cores(row.result.clientCpuNanos()),
                    row.result.peakBacklog());
        }
    }

    private static double micros(final Histogram latencies,
                                 final double percentile) {
        return latencies.getValueAtPercentile(percentile) / NANOS_PER_MICRO;
    }

    private static final class Row {
        private final String name;
        private final int channels;
        private final long offeredRate;
        private final LoadResult result;
        private final JvmStats server;

        private Row(final String name,
                    final int channels,
                    final long offeredRate,
                    final LoadResult result,
                    final JvmStats server) {
            this.name = name;
            this.channels = channels;
            this.offeredRate = offeredRate;
            this.result = result;
            this.server = server;
        }

        /**
         * @return what the publishers actually managed, in messages per second per channel. A server whose
         *         publisher blocks on its slowest subscriber falls short of the offered rate here while
         *         losing nothing and dropping nobody, so this is the only place such a run says so
         */
        private double achievedRate() {
            if (channels < 1 || result.clients() < 1) {
                return Double.NaN;
            }
            final long perChannel = result.published() / channels / result.clients();
            return perChannel / (result.elapsedNanos() / NANOS_PER_SECOND);
        }


        /**
         * @param cpuNanos one side used over the window
         * @return how many cores that side kept busy
         */
        private String cores(final long cpuNanos) {
            if (cpuNanos < 0) {
                return "n/a";
            }
            return String.format("%.2f", (double) cpuNanos / result.elapsedNanos());
        }

        /**
         * @return requests answered per second of server processor time, or NaN where the JVM did not
         *         report what it used
         */
        private double requestsPerCoreSecond() {
            if (server.processCpuNanos() <= 0) {
                return Double.NaN;
            }
            return result.requests() / (server.processCpuNanos() / NANOS_PER_SECOND);
        }

        private String requestsPerCoreSecondText() {
            final double value = requestsPerCoreSecond();
            return Double.isNaN(value) ? "n/a" : String.format("%.0f", value);
        }

        /**
         * Whether this side fell far enough behind for its tail to stop being a property of the server. The
         * percentiles themselves stay true - they are timed from when each request was due, so a queue is
         * counted, which is the point of an open loop - but once a server is past capacity the queue keeps
         * growing, and how long the tail gets then depends on how long the run lasted.
         *
         * @param rate offered, in requests per second
         * @return whether it ended up more than a quarter of a second in arrears
         */
        private boolean fellBehind(final long rate) {
            return result.peakBacklog() > rate / 4;
        }

        static final String OK = "ok";

        /**
         * Whether this row was served, and if not, what first said it was not - measured by the client from
         * what arrived, when, and in what order.
         *
         * @param lagMillis a subscriber may be behind by and still count as served
         * @return the verdict
         */
        private String verdict(final int lagMillis) {
            if (result.gaps() > 0) {
                return "holes";
            }
            if (result.reordered() > 0) {
                return "shuffled";
            }
            if (result.ioErrors() > 0) {
                return "dropped";
            }
            if (achievedRate() < offeredRate * BEHIND_THRESHOLD) {
                return "behind";
            }
            if (p99Micros() > lagMillis * MICROS_PER_MILLI) {
                return "lag";
            }
            return OK;
        }

        private double p99Micros() {
            final Histogram latencies = result.latencies();
            return latencies == null
                    ? Double.NaN
                    : latencies.getValueAtPercentile(99.0) / NANOS_PER_MICRO;
        }

        /**
         * @return collections over the window, or n/a where the server never reported the end of it
         */
        private String collections() {
            return server.isAvailable() ? Long.toString(server.gcCount()) : "n/a";
        }

        /**
         * @return milliseconds collecting over the window, or n/a for the same reason
         */
        private String collectionMillis() {
            return server.isAvailable() ? Long.toString(server.gcTimeMillis()) : "n/a";
        }

        private String allocationPerRequest() {
            if (server.allocatedBytes() < 0) {
                return "n/a";
            }
            if (result.requests() == 0) {
                return "-";
            }
            return String.format("%.1f", (double) server.allocatedBytes() / result.requests());
        }
    }
}
