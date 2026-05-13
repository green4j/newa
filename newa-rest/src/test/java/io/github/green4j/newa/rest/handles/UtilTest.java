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
