/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance;

import org.HdrHistogram.Histogram;

/**
 * What one run of the load client against one server produced.
 */
public final class LoadResult {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;

    private final Mode mode;
    private final int clients;
    private final long requests;
    private final long responseBytes;
    private final long published;
    private final long gaps;
    private final long reordered;
    private final long badStatuses;
    private final long ioErrors;
    private final long reconnects;
    private final long peakBacklog;
    private final long elapsedNanos;
    private final long clientCpuNanos;
    private final Histogram latencies;

    LoadResult(final Mode mode,
               final int clients,
               final long requests,
               final long responseBytes,
               final long published,
               final long gaps,
               final long reordered,
               final long badStatuses,
               final long ioErrors,
               final long reconnects,
               final long peakBacklog,
               final long elapsedNanos,
               final long clientCpuNanos,
               final Histogram latencies) {
        this.mode = mode;
        this.clients = clients;
        this.requests = requests;
        this.responseBytes = responseBytes;
        this.published = published;
        this.gaps = gaps;
        this.reordered = reordered;
        this.badStatuses = badStatuses;
        this.ioErrors = ioErrors;
        this.reconnects = reconnects;
        this.peakBacklog = peakBacklog;
        this.elapsedNanos = elapsedNanos;
        this.clientCpuNanos = clientCpuNanos;
        this.latencies = latencies;
    }

    /**
     * What a fan-out run produced, which differs from a request/response one in what it can be short of:
     * nothing is asked for, so nothing can be refused, and a stream can only be behind, holed or cut.
     *
     * @param clients  subscribers the run carried
     * @param frames   delivered over the window
     * @param bytes    delivered over the window
     * @param published the subscribers should have been given, taken from how far the sequence moved
     * @param gaps     places where the sequence jumped - a message which had not arrived when the one
     *                 after it did
     * @param reordered messages which arrived after one with a higher sequence, and so filled a jump in
     *                 late. Nothing was lost where these match the gaps: the stream was shuffled
     * @param dropped  subscribers the server disconnected
     * @param elapsedNanos the measured window lasted
     * @param latencies one way, from the instant of publication carried in each message
     * @return the result
     */
    public static LoadResult fanout(final int clients,
                                    final long frames,
                                    final long bytes,
                                    final long published,
                                    final long gaps,
                                    final long reordered,
                                    final long dropped,
                                    final long elapsedNanos,
                                    final Histogram latencies) {
        return new LoadResult(Mode.LATENCY, clients, frames, bytes, published, gaps, reordered,
                0L, dropped, 0L, 0L, elapsedNanos, 0L, latencies);
    }

    /**
     * @param cpuNanos the load client's own JVM used over the measured window
     * @return the same result, knowing what it cost to produce
     */
    public LoadResult withClientCpu(final long cpuNanos) {
        return new LoadResult(mode, clients, requests, responseBytes, published, gaps, reordered,
                badStatuses, ioErrors, reconnects, peakBacklog, elapsedNanos, cpuNanos, latencies);
    }

    /**
     * @return what the load client's own JVM used over the measured window, in nanoseconds
     */
    public long clientCpuNanos() {
        return clientCpuNanos;
    }

    public Mode mode() {
        return mode;
    }

    public int clients() {
        return clients;
    }

    public long requests() {
        return requests;
    }

    /**
     * @return how many items the server said it had produced over the window, against which {@link
     *         #requests()} is what actually arrived. A request/response run leaves it at zero: there the two
     *         are the same number by construction, and only a stream can be short without anybody erroring
     */
    public long published() {
        return published;
    }

    /**
     * @return places where the sequence of a stream jumped. Zero in a request/response run, and zero in any
     *         streaming run worth reading
     */
    public long gaps() {
        return gaps;
    }

    /**
     * @return messages which arrived after one with a higher sequence. A stream which reordered but lost
     *         nothing has as many of these as it has {@link #gaps()}, and delivered everything it published
     */
    public long reordered() {
        return reordered;
    }

    /**
     * @return responses which came back with a status other than 200. They are not counted as requests: a
     *         server which answers faster by refusing is not answering
     */
    public long badStatuses() {
        return badStatuses;
    }

    /**
     * @return connections which failed, threw, or were closed with a request still outstanding
     */
    public long ioErrors() {
        return ioErrors;
    }

    /**
     * @return how many times a connection had to be re-established during the run. Anything but zero means
     *         the server closed connections under the client, which is worth knowing before reading the rest
     */
    public long reconnects() {
        return reconnects;
    }

    /**
     * @return the largest number of requests which were due but had no free connection to go out on.
     *         Meaningful in {@link Mode#LATENCY} only, where it says the offered rate was beyond capacity
     */
    public long peakBacklog() {
        return peakBacklog;
    }

    /**
     * @return the latency distribution in nanoseconds, or null in {@link Mode#THROUGHPUT}
     */
    public Histogram latencies() {
        return latencies;
    }

    /**
     * @return how long the measured window lasted, in nanoseconds
     */
    public long elapsedNanos() {
        return elapsedNanos;
    }

    public double requestsPerSecond() {
        return requests / (elapsedNanos / NANOS_PER_SECOND);
    }

    public double megabytesPerSecond() {
        return responseBytes / BYTES_PER_MEGABYTE / (elapsedNanos / NANOS_PER_SECOND);
    }
}
