/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.ServerMemoryEstimate;

/**
 * Turns the final REST and transport settings into admission accounting. The estimate deliberately charges
 * a rendered response to every connection, even though event-loop serialization means only a worker-sized
 * subset renders at once: a memory budget is expected to prefer a conservative refusal to an optimistic
 * admission. Compression includes the source and a conservative upper bound for its encoded buffer.
 */
final class RestServerMemoryEstimator {
    /**
     * A connection may own two requests at once: the one being answered, and one the codec had already
     * decoded from the same network read, which the exchange gate holds until that answer is written.
     */
    private static final int REQUESTS_PER_CONNECTION = 2;

    private int maxContentLength;
    private int maxInitialLineLength;
    private int maxHeaderSize;
    private int maxResponseSize;
    private int chunkSize;
    private int writeBufferWaterMarkHigh;
    private boolean compression;
    private long additionalHeap;
    private long additionalDirectMemory;

    static RestServerMemoryEstimator builder() {
        return new RestServerMemoryEstimator();
    }

    RestServerMemoryEstimator request(final int contentLength,
                                      final int initialLineLength,
                                      final int headerSize) {
        maxContentLength = contentLength;
        maxInitialLineLength = initialLineLength;
        maxHeaderSize = headerSize;
        return this;
    }

    RestServerMemoryEstimator response(final int responseSize,
                                       final int responseChunkSize) {
        maxResponseSize = responseSize;
        chunkSize = responseChunkSize;
        return this;
    }

    RestServerMemoryEstimator transport(final int writeWaterMarkHigh,
                                        final boolean compress) {
        writeBufferWaterMarkHigh = writeWaterMarkHigh;
        compression = compress;
        return this;
    }

    RestServerMemoryEstimator additional(final long heap,
                                         final long directMemory) {
        additionalHeap = heap;
        additionalDirectMemory = directMemory;
        return this;
    }

    ServerMemoryEstimate estimate() {
        final long requestMetadata = multiply(
                add(maxInitialLineLength, maxHeaderSize),
                REQUESTS_PER_CONNECTION
        );
        final long heap = add(add(requestMetadata, maxResponseSize), additionalHeap);

        final long fullResponse = compression
                ? add(maxResponseSize, compressedUpperBound(maxResponseSize))
                : maxResponseSize;
        final long chunkedResponse = compression
                ? add(
                        add(writeBufferWaterMarkHigh, chunkSize),
                        compressedUpperBound(chunkSize)
                )
                : add(writeBufferWaterMarkHigh, chunkSize);
        final long outbound = Math.max(fullResponse, chunkedResponse);
        final long requestBodies = multiply(maxContentLength, REQUESTS_PER_CONNECTION);
        final long direct = add(add(requestBodies, outbound), additionalDirectMemory);

        return ServerMemoryEstimate.of(heap, direct);
    }

    private static long compressedUpperBound(final long sourceBytes) {
        long bound = sourceBytes;
        bound = add(bound, sourceBytes >>> 12);
        bound = add(bound, sourceBytes >>> 14);
        bound = add(bound, sourceBytes >>> 25);
        return add(bound, 64);
    }

    private static long multiply(final long value,
                                 final int multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long add(final long left,
                            final long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
