/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

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
