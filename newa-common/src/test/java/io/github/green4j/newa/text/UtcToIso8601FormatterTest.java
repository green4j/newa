/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UtcToIso8601FormatterTest {

    @ParameterizedTest
    @CsvSource({
        // the epoch itself, and a timestamp with every field set to something of its own
        "0,             1970-01-01T00:00:00.000Z",
        "1705314645123, 2024-01-15T10:30:45.123Z",
        // either side of a day boundary: the cached date has to be given up at exactly the right instant
        "86399999,      1970-01-01T23:59:59.999Z",
        "86400001,      1970-01-02T00:00:00.001Z"
    })
    public void formatsAnInstant(final long millis,
                                 final String expected) {
        Assertions.assertEquals(expected, new UtcToIso8601Formatter().format(millis).toString());
    }

    @ParameterizedTest
    @CsvSource({
        "456, 1970-01-01T00:00:00.000456Z",
        "5,   1970-01-01T00:00:00.000005Z", // padded out to the six digits it is
        "0,   1970-01-01T00:00:00.000000Z", // and printed even when there is nothing to say
        "-1,  1970-01-01T00:00:00.000Z"     // while a negative one is how the field is left out
    })
    public void formatsMicroseconds(final int micros,
                                    final String expected) {
        Assertions.assertEquals(expected, new UtcToIso8601Formatter().format(0, micros).toString());
    }

    @Test
    public void testSameDayCaching() {
        final UtcToIso8601Formatter fmt = new UtcToIso8601Formatter();
        final long baseMs = 3600_000L;
        final String first = fmt.format(baseMs).toString();
        final String second = fmt.format(baseMs + 1000).toString();

        Assertions.assertTrue(first.startsWith("1970-01-01T"));
        Assertions.assertTrue(second.startsWith("1970-01-01T"));
        Assertions.assertNotEquals(first, second);
    }
}
