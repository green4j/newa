/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

/**
 * The estimated memory one accepted connection reserves from a {@link ServerMemoryBudget}.
 * <p>
 * It is admission accounting rather than allocation accounting: nothing here observes the heap or intercepts
 * an allocator. A server derives the two numbers from its configured limits, and an application may add what
 * its own session, observer or handler state costs. Accuracy is therefore the caller's, deliberately - the
 * same estimate a deployment would otherwise turn into a fixed connection limit is shared dynamically
 * between servers instead.
 */
public final class ServerMemoryEstimate {
    private final long heapBytesPerConnection;
    private final long directMemoryBytesPerConnection;

    private ServerMemoryEstimate(final long heapBytesPerConnection,
                                 final long directMemoryBytesPerConnection) {
        this.heapBytesPerConnection = heapBytesPerConnection;
        this.directMemoryBytesPerConnection = directMemoryBytesPerConnection;
    }

    /**
     * @param heapBytesPerConnection estimated heap bytes held by one admitted connection
     * @param directMemoryBytesPerConnection estimated direct-memory bytes held by one admitted connection
     * @return the estimate
     */
    public static ServerMemoryEstimate of(final long heapBytesPerConnection,
                                          final long directMemoryBytesPerConnection) {
        if (heapBytesPerConnection < 0) {
            throw new IllegalArgumentException(
                    "heapBytesPerConnection must not be negative: " + heapBytesPerConnection);
        }
        if (directMemoryBytesPerConnection < 0) {
            throw new IllegalArgumentException(
                    "directMemoryBytesPerConnection must not be negative: "
                            + directMemoryBytesPerConnection);
        }
        if (heapBytesPerConnection == 0 && directMemoryBytesPerConnection == 0) {
            throw new IllegalArgumentException("A connection estimate must reserve some memory");
        }
        return new ServerMemoryEstimate(heapBytesPerConnection, directMemoryBytesPerConnection);
    }

    public long heapBytesPerConnection() {
        return heapBytesPerConnection;
    }

    public long directMemoryBytesPerConnection() {
        return directMemoryBytesPerConnection;
    }
}
