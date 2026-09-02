/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
