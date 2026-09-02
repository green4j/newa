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

package io.github.green4j.newa.rest.handles;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    public void testToMemorySizeBytes() {
        Assertions.assertEquals("0B", Util.toMemorySize(0));
        Assertions.assertEquals("512B", Util.toMemorySize(512));
        Assertions.assertEquals("1023B", Util.toMemorySize(1023));
    }

    @Test
    public void testToMemorySizeKilobytes() {
        Assertions.assertEquals("1.0KB",
                Util.toMemorySize(1024));
        Assertions.assertEquals("1.5KB",
                Util.toMemorySize(1536));
    }

    @Test
    public void testToMemorySizeMegabytes() {
        Assertions.assertEquals("1.0MB",
                Util.toMemorySize(1024 * 1024));
        Assertions.assertEquals("10.0MB",
                Util.toMemorySize(10 * 1024 * 1024));
    }

    @Test
    public void testToMemorySizeGigabytes() {
        Assertions.assertEquals("1.0GB",
                Util.toMemorySize(1024L * 1024 * 1024));
    }

    @Test
    public void testToMemorySizeTerabytes() {
        Assertions.assertEquals("1.0TB",
                Util.toMemorySize(1024L * 1024 * 1024 * 1024));
    }

    @Test
    public void testToDurationZero() {
        Assertions.assertEquals("0s0", Util.toDuration(0));
    }

    @Test
    public void testToDurationMillisOnly() {
        Assertions.assertEquals("0s500", Util.toDuration(500));
    }

    @Test
    public void testToDurationSeconds() {
        Assertions.assertEquals("5s0", Util.toDuration(5000));
    }

    @Test
    public void testToDurationMinutes() {
        Assertions.assertEquals("2m30s0",
                Util.toDuration(150_000));
    }

    @Test
    public void testToDurationHours() {
        Assertions.assertEquals("1h0s0",
                Util.toDuration(3_600_000));
    }

    @Test
    public void testToDurationDays() {
        Assertions.assertEquals("1d0s0",
                Util.toDuration(86_400_000));
    }

    @Test
    public void testToDurationCombined() {
        final long ms = 86_400_000L + 3_600_000
                + 60_000 + 1_000 + 1;
        Assertions.assertEquals("1d1h1m1s1",
                Util.toDuration(ms));
    }

    @Test
    public void testInSleepMatch() {
        final StackTraceElement ste = new StackTraceElement(
                "java.lang.Thread", "sleep",
                "Thread.java", 100);
        Assertions.assertTrue(Util.inSleep(ste));
    }

    @Test
    public void testInSleepNoMatch() {
        final StackTraceElement ste = new StackTraceElement(
                "java.lang.Object", "wait",
                "Object.java", 50);
        Assertions.assertFalse(Util.inSleep(ste));
    }

    @Test
    public void testInSleepWrongMethod() {
        final StackTraceElement ste = new StackTraceElement(
                "java.lang.Thread", "run",
                "Thread.java", 200);
        Assertions.assertFalse(Util.inSleep(ste));
    }
}
