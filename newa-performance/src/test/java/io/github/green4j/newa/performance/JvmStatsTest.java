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

package io.github.green4j.newa.performance;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A sweep goes on until the servers stop coping, so it reaches rates at which one of them stops answering
 * the statistics endpoint. What that must not do is end the sweep and take every row measured before it
 * down as well - the rows are what the run is for, and a server too busy to answer is a result of its own.
 */
public class JvmStatsTest {
    private static final long SECOND_NANOS = 1_000_000_000L;

    @Test
    public void statisticsSurviveBeingRenderedAndParsedBack() {
        final JvmStats parsed = JvmStats.parse(JvmStats.current().render());

        assertTrue(parsed.isAvailable());
        assertTrue(parsed.gcCount() >= 0);
        assertTrue(parsed.heapUsedBytes() > 0);
    }

    @Test
    public void aWindowWithoutAnEndIsNotAWindow() {
        final JvmStats measured = JvmStats.current();

        assertFalse(JvmStats.UNAVAILABLE.since(measured).isAvailable());
        assertFalse(measured.since(JvmStats.UNAVAILABLE).isAvailable());
        assertTrue(measured.since(measured).isAvailable());
    }

    /**
     * The collection counts of a window which has no end are not zero, they are unknown - and a table which
     * prints them as a number invites somebody to read a server which stopped answering as a server which
     * collected nothing.
     */
    @Test
    public void aRowAgainstASilentServerKeepsWhatArrivedAndClaimsNothingElse() {
        final Histogram latencies = new Histogram(3);
        latencies.recordValue(1000);

        final Report report = new Report();
        report.addFanout("spring-stomp", 1, 20000,
                LoadResult.fanout(100, 1_000_000, 200_000_000, 2_000_000, 7, 0, 3,
                        30 * SECOND_NANOS, latencies),
                JvmStats.UNAVAILABLE.since(JvmStats.current()));

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        report.printFanout(new PrintStream(bytes, true, StandardCharsets.US_ASCII),
                new BenchmarkOptions());
        final String printed = bytes.toString(StandardCharsets.US_ASCII);

        assertTrue(printed.contains("the sequence jumped 7 times"),
                "holes belong under the table, not in a column of zeroes: " + printed);

        final String[] rows = rowsOf(printed, "spring-stomp");

        final String delivered = rows[0];
        assertTrue(delivered.contains("33333"), "what arrived is the client's to count: " + delivered);
        assertTrue(delivered.contains("n/a"), "what it cost per core is not: " + delivered);

        // server, ch, clients, srv cores, cli cores, gc, gcMs, alloc B/frame
        final String[] cost = rows[1].trim().split("\\s+");
        assertEquals("n/a", cost[3], "server cores: " + rows[1]);
        assertEquals("n/a", cost[5], "collections: " + rows[1]);
        assertEquals("n/a", cost[6], "time collecting: " + rows[1]);
        assertEquals("n/a", cost[7], "allocation per frame: " + rows[1]);
    }

    /**
     * @param printed report to look in
     * @param server  whose rows to find
     * @return the two lines this server has in the report - what it delivered, and what that cost
     */
    private static String[] rowsOf(final String printed,
                                   final String server) {
        final String[] lines = printed.split("\n");
        final List<String> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            // a table row, not the sentence printed under the table when something went wrong
            if (lines[i].startsWith(server) && lines[i].matches("\\S+\\s+\\d+\\s+.*")) {
                rows.add(lines[i]);
            }
        }
        if (rows.size() != 2) {
            fail("expected a delivery row and a cost row for " + server + ", got " + rows.size()
                    + ":\n" + printed);
        }
        return rows.toArray(new String[0]);
    }
}
