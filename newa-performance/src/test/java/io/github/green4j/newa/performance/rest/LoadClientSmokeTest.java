/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest;

import io.github.green4j.newa.performance.LoadClient;
import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoadClientSmokeTest {
    private static final int CLIENTS = 4;
    private static final long RATE = 2000;
    private static final int DURATION_SECONDS = 1;

    @Test
    public void throughputModeDrivesTheServerAndCountsWhatCameBack() throws Exception {
        final LoadResult result = run(Mode.THROUGHPUT);

        assertTrue(result.requests() > 0, "No request completed");
        assertEquals(0, result.badStatuses());
        assertEquals(0, result.ioErrors());
        assertEquals(0, result.reconnects());
        assertTrue(result.megabytesPerSecond() > 0);
        assertNull(result.latencies(), "Throughput mode must not report percentiles it cannot justify");
    }

    @Test
    public void latencyModeRecordsWhatItOffered() throws Exception {
        final LoadResult result = run(Mode.LATENCY);

        assertTrue(result.requests() > 0, "No request completed");
        assertEquals(0, result.badStatuses());
        assertEquals(0, result.ioErrors());
        assertNotNull(result.latencies());
        assertEquals(result.requests(), result.latencies().getTotalCount());
        assertTrue(result.latencies().getValueAtPercentile(50.0) > 0);
    }

    private static LoadResult run(final Mode mode) throws Exception {
        try (RestServer server = RestServerMain.start(RestServerMain.NEWA, 0, 1)) {
            try (LoadClient client = new LoadClient(
                    mode, "127.0.0.1", server.port(), CLIENTS, RATE, RestPayload.PATH_PREFIX)) {
                return client.run(0, DURATION_SECONDS);
            }
        }
    }
}
