/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

class AsciiByteCharSequenceTest {

    @Test
    public void testLengthReflectsSetLength() {
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(10);
        Assertions.assertEquals(0, cs.length());
        cs.setLength(5);
        Assertions.assertEquals(5, cs.length());
    }

    @Test
    public void testCharAt() {
        final byte[] data = "Hello".getBytes(StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals('H', cs.charAt(0));
        Assertions.assertEquals('e', cs.charAt(1));
        Assertions.assertEquals('o', cs.charAt(4));
    }

    /**
     * @param data     to wrap.
     * @param length   to set, which is what says where the sequence ends rather than the array does.
     * @param expected the sequence to read as.
     */
    @ParameterizedTest
    @CsvSource({
        "World,       5, World",
        "Hello World, 5, Hello"
    })
    public void toStringReadsAsFarAsTheLength(final String data,
                                              final int length,
                                              final String expected) {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data.getBytes(StandardCharsets.US_ASCII));
        cs.setLength(length);

        Assertions.assertEquals(expected, cs.toString());
    }

    /**
     * @param data     to wrap, taken whole.
     * @param start    of the sub-sequence.
     * @param end      of it, which is where it stops rather than how long it is.
     * @param expected what those two indexes cover.
     */
    @ParameterizedTest
    @CsvSource({
        "Hello World,  0,  5, Hello",
        "Hello World,  6, 11, World",
        "Hello World,  2,  5, llo",
        "Hello World,  4,  4, ''",
        "Hello World,  0, 11, Hello World"
    })
    public void subSequenceCoversFromStartToEnd(final String data,
                                                final int start,
                                                final int end,
                                                final String expected) {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data.getBytes(StandardCharsets.US_ASCII));
        cs.setLength(data.length());

        final CharSequence sub = cs.subSequence(start, end);

        Assertions.assertEquals(end - start, sub.length());
        Assertions.assertEquals(expected, sub.toString());
    }

    /**
     * @param length to set on a ten byte array.
     * @param start  of the sub-sequence asked for.
     * @param end    of it.
     */
    @ParameterizedTest
    @CsvSource({
        "5, 0,  6",   // past the length, though not past the array
        "5, 3,  2",   // ends before it starts
        "5, -1, 3"    // starts before the sequence does
    })
    public void subSequenceRefusesWhatIsNotThere(final int length,
                                                 final int start,
                                                 final int end) {
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(10);
        cs.setLength(length);

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> cs.subSequence(start, end));
    }

    @Test
    public void everythingReadsTheSameBytesTheSameWay() {
        // one rule, so a sub-sequence of the whole is the whole, whatever the platform charset says
        final byte[] data = "Report 42".getBytes(StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals(cs.toString(), cs.subSequence(0, cs.length()).toString());
        Assertions.assertEquals(String.valueOf(cs.charAt(7)), cs.subSequence(7, 8).toString());
    }

    @Test
    public void testBytesReturnsUnderlyingArray() {
        final byte[] data = {65, 66, 67};
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(data);
        Assertions.assertSame(data, cs.bytes());
    }

    @Test
    public void testConstructorWithSize() {
        final AsciiByteCharSequence cs = new AsciiByteCharSequence(32);
        Assertions.assertEquals(32, cs.bytes().length);
        Assertions.assertEquals(0, cs.length());
    }
}
