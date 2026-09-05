/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ByteRangeTest {
    private static final long SIZE = 1000;

    @ParameterizedTest
    @CsvSource({
        "bytes=100-199,  100, 100",
        "bytes=900-,     900, 100",  // a first byte alone runs to the end
        "bytes=-100,     900, 100",  // and a suffix counts back from it
        "bytes=-5000,      0, 1000", // a suffix longer than the file is the whole of it
        "bytes=990-5000, 990, 10",   // a last byte past the end is clamped to it
        "bytes=0-,         0, 1000",
        "BYTES=0-1,        0, 2"     // the unit is named without regard to case
    })
    public void aRangeOfTheFile(final String header,
                                final long offset,
                                final long length) {
        final ByteRange range = ByteRange.parse(header, SIZE);

        Assertions.assertNotNull(range, header);
        Assertions.assertEquals(offset, range.offset(), header);
        Assertions.assertEquals(length, range.length(), header);
    }

    /**
     * @param header a range which names no byte the file has, and which must be refused rather than
     *               quietly answered with something else.
     * @param size   of the file it is asked of.
     */
    @ParameterizedTest
    @CsvSource({
        "bytes=1000-,     1000", // the first byte past the end
        "bytes=5000-6000, 1000",
        "bytes=-0,        1000", // a suffix of nothing
        "bytes=0-,        0",    // no byte of an empty file can be asked for
        "bytes=-10,       0"
    })
    public void aRangeWhichCannotBeSatisfied(final String header,
                                             final long size) {
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse(header, size), header);
    }

    /**
     * A header this server does not understand is not an error: RFC 9110 says to ignore it and send the
     * whole file, which is what a null stands for here. The missing header is the same answer.
     *
     * @param header which names no range this server can make sense of.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "bytes=0-99,200-299", // several ranges
        "items=0-99",         // another unit
        "bytes=199-100",      // backwards
        "bytes=-",
        "bytes=",
        "bytes=abc-def",
        "bytes=1-2-3",
        "nonsense"
    })
    public void whatIsNotUnderstoodIsIgnoredRatherThanRefused(final String header) {
        Assertions.assertNull(ByteRange.parse(header, SIZE), String.valueOf(header));
    }
}
