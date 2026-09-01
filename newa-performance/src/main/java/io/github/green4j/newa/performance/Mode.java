package io.github.green4j.newa.performance;

/**
 * What a run measures. Never both at once: the client which saturates a server cannot also tell you what its
 * latency is at a given load, and the client which offers a fixed load is not measuring how much it could
 * have offered.
 */
public enum Mode {
    /**
     * Closed loop. Every connection sends its next request the moment the previous response arrives, so the
     * offered load is whatever the server can take, and what is reported is how much that was.
     */
    THROUGHPUT,

    /**
     * Open loop at a fixed offered rate. Requests are issued on a schedule which does not wait for the
     * server, and each one is timed from the instant it was due rather than the instant it went out.
     */
    LATENCY;

    public static Mode parse(final String value) {
        for (final Mode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + value
                + ". Expected 'throughput' or 'latency'");
    }
}
