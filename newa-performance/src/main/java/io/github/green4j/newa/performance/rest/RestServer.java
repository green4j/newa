package io.github.green4j.newa.performance.rest;

/**
 * A server under test. Both implementations start on a port, answer the same two endpoints, and stop.
 */
public interface RestServer extends AutoCloseable {
    /**
     * @return the port actually bound, which is what a caller who asked for port 0 needs
     */
    int port();

    @Override
    void close();
}
