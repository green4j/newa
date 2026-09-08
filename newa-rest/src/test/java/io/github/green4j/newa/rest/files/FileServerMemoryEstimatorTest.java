/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.server.ServerMemoryEstimate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FileServerMemoryEstimatorTest {
    @Test
    void accountsForAWriteAndOneReadAheadChunk() {
        final ServerMemoryEstimate estimate = estimate(false);

        Assertions.assertEquals(67, estimate.heapBytesPerConnection());
        Assertions.assertEquals(371, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void aConnectionIsChargedForTwoRequests() {
        // the one being answered, and the one the exchange gate may be holding behind it
        final ServerMemoryEstimate estimate = estimate(false);
        final ServerMemoryEstimate larger = FileServerMemoryEstimator.builder()
                .request(1100, 10, 20)
                .file(50)
                .transport(60, false)
                .additional(7, 11)
                .estimate();

        Assertions.assertEquals(
                2 * 1000,
                larger.directMemoryBytesPerConnection() - estimate.directMemoryBytesPerConnection()
        );
    }

    @Test
    void compressionAccountsForSourceAndEncodedStaging() {
        final ServerMemoryEstimate estimate = estimate(true);

        Assertions.assertEquals(485, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void arithmeticSaturatesInsteadOfWrapping() {
        final ServerMemoryEstimate estimate = FileServerMemoryEstimator.builder()
                .request(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                .file(Integer.MAX_VALUE)
                .transport(Integer.MAX_VALUE, true)
                .additional(Long.MAX_VALUE, Long.MAX_VALUE)
                .estimate();

        Assertions.assertEquals(Long.MAX_VALUE, estimate.heapBytesPerConnection());
        Assertions.assertEquals(Long.MAX_VALUE, estimate.directMemoryBytesPerConnection());
    }

    private static ServerMemoryEstimate estimate(final boolean compression) {
        return FileServerMemoryEstimator.builder()
                .request(100, 10, 20)
                .file(50)
                .transport(60, compression)
                .additional(7, 11)
                .estimate();
    }
}
