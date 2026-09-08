/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.server.ServerMemoryEstimate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RestServerMemoryEstimatorTest {
    @Test
    void accountsForTheLargerOfAFullAndAChunkedResponse() {
        final ServerMemoryEstimate full = estimate(200, false);
        final ServerMemoryEstimate chunked = estimate(80, false);

        Assertions.assertEquals(267, full.heapBytesPerConnection());
        Assertions.assertEquals(411, full.directMemoryBytesPerConnection());

        Assertions.assertEquals(147, chunked.heapBytesPerConnection());
        Assertions.assertEquals(321, chunked.directMemoryBytesPerConnection());
    }

    @Test
    void aConnectionIsChargedForTwoRequests() {
        // the one being answered, and the one the exchange gate may be holding behind it
        final ServerMemoryEstimate estimate = estimate(200, false);
        final ServerMemoryEstimate larger = RestServerMemoryEstimator.builder()
                .request(1100, 10, 20)
                .response(200, 50)
                .transport(60, false)
                .additional(7, 11)
                .estimate();

        Assertions.assertEquals(
                2 * 1000,
                larger.directMemoryBytesPerConnection() - estimate.directMemoryBytesPerConnection()
        );
    }

    @Test
    void compressionAccountsForSourceAndEncodedBuffers() {
        final ServerMemoryEstimate full = estimate(200, true);
        final ServerMemoryEstimate chunked = estimate(80, true);

        Assertions.assertEquals(675, full.directMemoryBytesPerConnection());
        Assertions.assertEquals(435, chunked.directMemoryBytesPerConnection());
    }

    @Test
    void arithmeticSaturatesInsteadOfWrapping() {
        final ServerMemoryEstimate estimate = RestServerMemoryEstimator.builder()
                .request(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                .response(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .transport(Integer.MAX_VALUE, true)
                .additional(Long.MAX_VALUE, Long.MAX_VALUE)
                .estimate();

        Assertions.assertEquals(Long.MAX_VALUE, estimate.heapBytesPerConnection());
        Assertions.assertEquals(Long.MAX_VALUE, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void aBudgetAndItsRequiredResponseEstimateAreConfiguredTogether() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RestServer.of(request -> null).withMemoryBudget(
                        ServerMemoryBudget.builder().build(),
                        0
                )
        );
    }

    private static ServerMemoryEstimate estimate(final int responseSize,
                                                 final boolean compression) {
        return RestServerMemoryEstimator.builder()
                .request(100, 10, 20)
                .response(responseSize, 50)
                .transport(60, compression)
                .additional(7, 11)
                .estimate();
    }
}
