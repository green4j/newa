/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.server.ServerMemoryEstimate;

/**
 * Admission accounting for a file connection. The pumped path is used even when the configured pipeline can
 * currently use zero-copy: TLS, compression or a transport change may make one chunk being written overlap
 * the next chunk being read ahead. Compression additionally includes the source and a conservative upper
 * bound for its encoded buffer.
 */
final class FileServerMemoryEstimator {
    /**
     * A connection may own two requests at once: the one being answered, and one the codec had already
     * decoded from the same network read, which the exchange gate holds until that answer is written.
     */
    private static final int REQUESTS_PER_CONNECTION = 2;

    private int maxContentLength;
    private int maxInitialLineLength;
    private int maxHeaderSize;
    private int chunkSize;
    private int writeBufferWaterMarkHigh;
    private boolean compression;
    private long additionalHeap;
    private long additionalDirectMemory;

    static FileServerMemoryEstimator builder() {
        return new FileServerMemoryEstimator();
    }

    FileServerMemoryEstimator request(final int contentLength,
                                      final int initialLineLength,
                                      final int headerSize) {
        maxContentLength = contentLength;
        maxInitialLineLength = initialLineLength;
        maxHeaderSize = headerSize;
        return this;
    }

    FileServerMemoryEstimator file(final int responseChunkSize) {
        chunkSize = responseChunkSize;
        return this;
    }

    FileServerMemoryEstimator transport(final int writeWaterMarkHigh,
                                        final boolean compress) {
        writeBufferWaterMarkHigh = writeWaterMarkHigh;
        compression = compress;
        return this;
    }

    FileServerMemoryEstimator additional(final long heap,
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
        final long heap = add(requestMetadata, additionalHeap);
        final long uncompressed = multiply(chunkSize, 2);
        final long pumped = compression
                ? add(
                        add(writeBufferWaterMarkHigh, uncompressed),
                        compressedUpperBound(chunkSize)
                )
                : add(writeBufferWaterMarkHigh, uncompressed);
        final long requestBodies = multiply(maxContentLength, REQUESTS_PER_CONNECTION);
        final long direct = add(add(requestBodies, pumped), additionalDirectMemory);
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
