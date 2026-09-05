/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.handles;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UtilTest {

    @ParameterizedTest
    @CsvSource({
        "0,               0B",
        "512,             512B",
        "1023,            1023B",    // the last one still counted in bytes
        "1024,            1.0KB",    // and the first which is not
        "1536,            1.5KB",
        "1048576,         1.0MB",
        "10485760,        10.0MB",
        "1073741824,      1.0GB",
        "1099511627776,   1.0TB"
    })
    public void toMemorySize(final long bytes,
                             final String expected) {
        Assertions.assertEquals(expected, Util.toMemorySize(bytes));
    }

    @ParameterizedTest
    @CsvSource({
        "0,          0s0",
        "500,        0s500",       // milliseconds alone still name the seconds they are none of
        "5000,       5s0",
        "150000,     2m30s0",
        "3600000,    1h0s0",
        "86400000,   1d0s0",
        "90061001,   1d1h1m1s1"     // every field at once
    })
    public void toDuration(final long millis,
                           final String expected) {
        Assertions.assertEquals(expected, Util.toDuration(millis));
    }

    @ParameterizedTest
    @CsvSource({
        "java.lang.Thread, sleep, Thread.java, true",
        "java.lang.Object, wait,  Object.java, false",  // another class's method of another name
        "java.lang.Thread, run,   Thread.java, false"   // the right class, and not the method
    })
    public void inSleep(final String className,
                        final String methodName,
                        final String fileName,
                        final boolean expected) {
        Assertions.assertEquals(expected,
                Util.inSleep(new StackTraceElement(className, methodName, fileName, 100)));
    }
}
