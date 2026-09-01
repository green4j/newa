package io.github.green4j.newa.performance;

/**
 * The shape of a run, taken from {@code newa.perf.*} system properties.
 * <p>
 * The Gradle tasks turn {@code -Pclients=1,100,1000} into {@code -Dnewa.perf.clients=1,100,1000}, and
 * {@link ServerProcess} passes the same properties on to the server JVM it forks, so one place decides what
 * a run looks like and both sides of it agree.
 */
public final class BenchmarkOptions {
    public static final String PREFIX = "newa.perf.";

    private static final String DEFAULT_CLIENTS = "100";

    /**
     * A run is warmed up for this long and then measured for this long. Anything shorter and the tail is
     * dominated by whatever the machine happened to be doing at the time.
     */
    private static final String DEFAULT_WARMUP_SECONDS = "10";
    private static final String DEFAULT_DURATION_SECONDS = "30";

    /**
     * How far behind a subscriber of a fan-out may be and still count as served, in milliseconds. It is the
     * service level a row is judged against rather than anything a server enforces, so the verdict describes
     * what was measured and not what a server does with a subscriber it gave up on.
     */
    private static final String DEFAULT_LAG_MILLIS = "100";

    private final Mode mode;
    private final int[] clients;
    private final int workers;
    private final long rate;
    private final int warmupSeconds;
    private final int durationSeconds;
    private final int port;
    private final String heap;
    private final int lagMillis;

    public BenchmarkOptions() {
        mode = Mode.parse(property("mode", "throughput"));
        clients = ints(property("clients", DEFAULT_CLIENTS));
        workers = Integer.parseInt(property("workers", Integer.toString(Cores.serverThreads())));
        // a list, because the fan-out benchmark sweeps the rate the way this one sweeps the clients. A
        // request/response run has one offered rate and takes the first of them
        rate = longs(property("rate", "50000"))[0];
        warmupSeconds = Integer.parseInt(property("warmup", DEFAULT_WARMUP_SECONDS));
        durationSeconds = Integer.parseInt(property("duration", DEFAULT_DURATION_SECONDS));
        port = Integer.parseInt(property("port", "9100"));
        heap = property("heap", "1g");
        lagMillis = Integer.parseInt(property("lag", DEFAULT_LAG_MILLIS));

        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1, got " + workers);
        }
        if (durationSeconds < 1) {
            throw new IllegalArgumentException("duration must be at least 1 second, got " + durationSeconds);
        }
    }

    /**
     * @return milliseconds a subscriber may be behind by and still count as served, as this run set it
     */
    public int lagMillis() {
        return lagMillis;
    }

    /**
     * @return the same, for a server which has no options object of its own
     */
    public static int lagMillisProperty() {
        return Integer.parseInt(property("lag", DEFAULT_LAG_MILLIS));
    }

    /**
     * @param name  of the option, without the {@link #PREFIX}
     * @param defaultValue to use when the option was not given
     * @return the value of the option
     */
    public static String property(final String name,
                                  final String defaultValue) {
        final String value = System.getProperty(PREFIX + name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /**
     * Reads an option which a run may sweep rather than fix - {@code -Pclients=1,100,1000}. Every value has
     * to be positive: a run of zero of anything is not a row, it is a mistake which would print as one.
     *
     * @param value of the option, one number or several separated by commas
     * @return the values, in the order they were given
     */
    public static int[] ints(final String value) {
        final String[] parts = value.split(",");
        final int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
            if (result[i] < 1) {
                throw new IllegalArgumentException("must be at least 1, got " + result[i]);
            }
        }
        return result;
    }

    /**
     * The same as {@link #ints(String)} for an option which does not fit an int - a rate, say.
     *
     * @param value of the option, one number or several separated by commas
     * @return the values, in the order they were given
     */
    public static long[] longs(final String value) {
        final String[] parts = value.split(",");
        final long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Long.parseLong(parts[i].trim());
            if (result[i] < 1) {
                throw new IllegalArgumentException("must be at least 1, got " + result[i]);
            }
        }
        return result;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * @return the connection counts to run, in order. Every one of them is run against every server
     */
    public int[] clients() {
        return clients;
    }

    /**
     * @return worker threads for the newa server. The Spring server ignores this and keeps Boot's defaults -
     *         a synchronous server meeting a thousand clients with its default pool is part of what is being
     *         measured
     */
    public int workers() {
        return workers;
    }

    /**
     * @return requests per second the client offers in {@link Mode#LATENCY}, the first of them if a list
     *         was given. Meaningless in the other mode
     */
    public long rate() {
        return rate;
    }

    public int warmupSeconds() {
        return warmupSeconds;
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    /**
     * @return port the forked server listens on
     */
    public int port() {
        return port;
    }

    /**
     * @return heap size given to the forked server, as a {@code -Xmx} argument
     */
    public String heap() {
        return heap;
    }
}
