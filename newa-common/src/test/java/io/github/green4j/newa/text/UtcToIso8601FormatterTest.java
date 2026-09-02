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

package io.github.green4j.newa.text;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

class UtcToIso8601FormatterTest {

    @Test
    public void testEpochZero() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final String result = fmt.format(0).toString();
        Assertions.assertEquals(
                "1970-01-01T00:00:00.000Z", result);
    }

    @Test
    public void testKnownTimestamp() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final Calendar cal = new GregorianCalendar(
                TimeZone.getTimeZone("GMT"));
        cal.set(2024, Calendar.JANUARY, 15, 10, 30, 45);
        cal.set(Calendar.MILLISECOND, 123);

        final String result =
                fmt.format(cal.getTimeInMillis()).toString();
        Assertions.assertEquals(
                "2024-01-15T10:30:45.123Z", result);
    }

    @Test
    public void testMidnightBoundary() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final long dayMs = 24L * 60 * 60 * 1000;

        final String beforeMidnight =
                fmt.format(dayMs - 1).toString();
        Assertions.assertTrue(
                beforeMidnight.startsWith("1970-01-01T"),
                "Before midnight: " + beforeMidnight);

        final String afterMidnight =
                fmt.format(dayMs + 1).toString();
        Assertions.assertTrue(
                afterMidnight.startsWith("1970-01-02T"),
                "After midnight: " + afterMidnight);
    }

    @Test
    public void testSameDayCaching() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final long baseMs = 3600_000L;
        final String first =
                fmt.format(baseMs).toString();
        final String second =
                fmt.format(baseMs + 1000).toString();

        Assertions.assertTrue(
                first.startsWith("1970-01-01T"));
        Assertions.assertTrue(
                second.startsWith("1970-01-01T"));
        Assertions.assertNotEquals(first, second);
    }

    @Test
    public void testMicroseconds() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final String result =
                fmt.format(0, 456).toString();
        Assertions.assertEquals(
                "1970-01-01T00:00:00.000456Z", result);
    }

    @Test
    public void testMicrosecondsPadding() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final String result =
                fmt.format(0, 5).toString();
        Assertions.assertEquals(
                "1970-01-01T00:00:00.000005Z", result);
    }

    @Test
    public void testMicrosecondsZero() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final String result =
                fmt.format(0, 0).toString();
        Assertions.assertEquals(
                "1970-01-01T00:00:00.000000Z", result);
    }

    @Test
    public void testNoMicroseconds() {
        final UtcToIso8601Formatter fmt =
                new UtcToIso8601Formatter();
        final String withoutMicros =
                fmt.format(0, -1).toString();
        Assertions.assertEquals(
                "1970-01-01T00:00:00.000Z",
                withoutMicros);
    }
}
