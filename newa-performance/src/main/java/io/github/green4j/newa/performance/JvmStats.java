/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * What a JVM has to say about a run: how much processor it used, how often it collected, how long that took,
 * and how much it allocated. Throughput alone does not distinguish a server which is fast from one which is
 * fast until the heap fills, and it does not say which side of a benchmark ran out of machine first - the
 * processor time is what turns "it stopped going up" into "this half was full".
 * <p>
 * Both sides are measured with it: the server serves it over HTTP, and the load client reads its own the same
 * way. The format lives in one place because the same class renders and parses it, and it is deliberately
 * plain text: the benchmark should not need a JSON parser to read its own instrumentation, and this endpoint
 * is never part of a measurement.
 */
public final class JvmStats {
    /**
     * Where a server publishes these. Also the readiness probe: a server which answers here has started.
     */
    public static final String PATH = "/v1/perf/stats";

    private static final String PROCESS_CPU_NANOS = "processCpuNanos";
    private static final String GC_COUNT = "gcCount";
    private static final String GC_TIME_MILLIS = "gcTimeMillis";
    private static final String HEAP_USED_BYTES = "heapUsedBytes";
    private static final String ALLOCATED_BYTES = "allocatedBytes";

    /**
     * Whether this runtime image has the {@code com.sun.management} extensions at all. Asked once, by name,
     * so that asking cannot itself fail - see {@link SunManagement}.
     */
    private static final boolean SUN_MANAGEMENT_PRESENT =
            classPresent("com.sun.management.OperatingSystemMXBean")
                    && classPresent("com.sun.management.ThreadMXBean");

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * What a server which did not answer the probe reports: nothing at all. A server far enough past capacity
     * stops answering HTTP, which is worth knowing rather than worth a stack trace, so the row is kept with
     * every column from here printed as {@code n/a}.
     */
    public static final JvmStats UNAVAILABLE = new JvmStats(false, -1, -1, -1, -1, -1);

    /**
     * Whether the numbers below are numbers. False only for {@link #UNAVAILABLE}; a JVM which cannot report
     * one of them says so with -1 in that field.
     */
    private final boolean available;

    private final long processCpuNanos;
    private final long gcCount;
    private final long gcTimeMillis;
    private final long heapUsedBytes;
    private final long allocatedBytes;

    private JvmStats(final long processCpuNanos,
                     final long gcCount,
                     final long gcTimeMillis,
                     final long heapUsedBytes,
                     final long allocatedBytes) {
        this(true, processCpuNanos, gcCount, gcTimeMillis, heapUsedBytes, allocatedBytes);
    }

    private JvmStats(final boolean available,
                     final long processCpuNanos,
                     final long gcCount,
                     final long gcTimeMillis,
                     final long heapUsedBytes,
                     final long allocatedBytes) {
        this.available = available;
        this.processCpuNanos = processCpuNanos;
        this.gcCount = gcCount;
        this.gcTimeMillis = gcTimeMillis;
        this.heapUsedBytes = heapUsedBytes;
        this.allocatedBytes = allocatedBytes;
    }

    /**
     * @return what this JVM has done so far
     */
    public static JvmStats current() {
        long count = 0;
        long time = 0;
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < collectors.size(); i++) {
            final GarbageCollectorMXBean collector = collectors.get(i);
            final long collections = collector.getCollectionCount();
            if (collections > 0) {
                count += collections;
            }
            final long millis = collector.getCollectionTime();
            if (millis > 0) {
                time += millis;
            }
        }
        return new JvmStats(
                currentProcessCpuNanos(),
                count,
                time,
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                totalAllocatedBytes()
        );
    }

    /**
     * @return processor time this JVM has used, over all its threads, or -1 where it is not reported
     */
    private static long currentProcessCpuNanos() {
        return SUN_MANAGEMENT_PRESENT ? SunManagement.processCpuNanos() : -1;
    }

    /**
     * @return bytes allocated by the threads alive now, or -1 where the JVM does not report it
     */
    private static long totalAllocatedBytes() {
        return SUN_MANAGEMENT_PRESENT ? SunManagement.allocatedBytes() : -1;
    }

    private static boolean classPresent(final String name) {
        try {
            Class.forName(name, false, JvmStats.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Every direct reference to {@code com.sun.management} is in here, and this class is touched only once
     * {@link #SUN_MANAGEMENT_PRESENT} has found the interfaces. Processor time and allocation are
     * extensions, not part of {@code java.lang.management}: an {@code instanceof} guard covers a JVM which
     * does not <i>implement</i> them, but a runtime image built without {@code jdk.management} does not
     * <i>contain</i> them, and there the guard itself is what fails to link. Keeping the references in a
     * class of their own is what turns that into a missing number rather than a
     * {@link NoClassDefFoundError}.
     */
    private static final class SunManagement {
        private SunManagement() {
        }

        static long processCpuNanos() {
            final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
            }
            return -1;
        }

        /**
         * Summed over the threads which are alive now rather than taken from
         * {@code getTotalThreadAllocatedBytes()}, which is only there from Java 21 and this module is built
         * for 17. Both servers keep their threads for the whole run, so nothing a measured window allocates
         * is lost.
         *
         * @return bytes allocated by the threads alive now, or -1 where the JVM does not report it
         */
        static long allocatedBytes() {
            final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (!(threads instanceof com.sun.management.ThreadMXBean)) {
                return -1;
            }
            final com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) threads;
            if (!sun.isThreadAllocatedMemorySupported() || !sun.isThreadAllocatedMemoryEnabled()) {
                return -1;
            }
            final long[] allocated = sun.getThreadAllocatedBytes(threads.getAllThreadIds());
            long total = 0;
            for (int i = 0; i < allocated.length; i++) {
                if (allocated[i] > 0) {
                    total += allocated[i];
                }
            }
            return total;
        }
    }

    /**
     * Reads the statistics off a running server. Doubles as the readiness probe: a server which answers here
     * has finished starting.
     *
     * @param host the server listens on
     * @param port the server listens on
     * @return what the server has done so far
     */
    public static JvmStats fetch(final String host,
                                    final int port) {
        final String uri = "http://" + host + ':' + port + PATH;
        final HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
        try {
            final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("The server answered " + response.statusCode()
                        + " to " + uri);
            }
            return parse(response.body());
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read " + uri, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading " + uri, e);
        }
    }

    /**
     * @param text as produced by {@link #render()}
     * @return the parsed statistics
     */
    public static JvmStats parse(final String text) {
        return new JvmStats(
                value(text, PROCESS_CPU_NANOS),
                value(text, GC_COUNT),
                value(text, GC_TIME_MILLIS),
                value(text, HEAP_USED_BYTES),
                value(text, ALLOCATED_BYTES)
        );
    }

    private static long value(final String text,
                              final String name) {
        final String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (line.startsWith(name + "=")) {
                return Long.parseLong(line.substring(name.length() + 1));
            }
        }
        throw new IllegalArgumentException("No '" + name + "' in the server statistics: " + text);
    }

    /**
     * @return the statistics as the text {@link #parse(String)} reads
     */
    public String render() {
        return PROCESS_CPU_NANOS + '=' + processCpuNanos + '\n'
                + GC_COUNT + '=' + gcCount + '\n'
                + GC_TIME_MILLIS + '=' + gcTimeMillis + '\n'
                + HEAP_USED_BYTES + '=' + heapUsedBytes + '\n'
                + ALLOCATED_BYTES + '=' + allocatedBytes + '\n';
    }

    /**
     * @param before the statistics taken at the start of the measured window
     * @return what happened between then and now. Heap used is left as it is now, not as a difference
     */
    public JvmStats since(final JvmStats before) {
        if (!available || !before.available) {
            return UNAVAILABLE; // one end of the window is missing, so the window itself is
        }
        return new JvmStats(
                difference(processCpuNanos, before.processCpuNanos),
                gcCount - before.gcCount,
                gcTimeMillis - before.gcTimeMillis,
                heapUsedBytes,
                allocatedBytes < 0 || before.allocatedBytes < 0 ? -1 : allocatedBytes - before.allocatedBytes
        );
    }

    private static long difference(final long now,
                                   final long before) {
        return now < 0 || before < 0 ? -1 : now - before;
    }

    /**
     * @return processor time used, in nanoseconds, or -1 where the JVM does not report it. Divided by how
     *         long the window lasted, this is how many cores the process was actually keeping busy
     */
    public long processCpuNanos() {
        return processCpuNanos;
    }

    /**
     * @return whether the server answered at all. A row against one which did not still reports what
     *         arrived; it just has nothing to say about what delivering it cost
     */
    public boolean isAvailable() {
        return available;
    }

    public long gcCount() {
        return gcCount;
    }

    public long gcTimeMillis() {
        return gcTimeMillis;
    }

    public long heapUsedBytes() {
        return heapUsedBytes;
    }

    /**
     * @return bytes allocated on the heap, or -1 where the JVM does not report it
     */
    public long allocatedBytes() {
        return allocatedBytes;
    }
}
