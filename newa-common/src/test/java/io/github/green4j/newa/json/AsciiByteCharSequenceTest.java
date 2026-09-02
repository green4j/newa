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

package io.github.green4j.newa.json;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class AsciiByteCharSequenceTest {

    @Test
    public void testLengthReflectsSetLength() {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(10);
        Assertions.assertEquals(0, cs.length());
        cs.setLength(5);
        Assertions.assertEquals(5, cs.length());
    }

    @Test
    public void testCharAt() {
        final byte[] data = "Hello".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals('H', cs.charAt(0));
        Assertions.assertEquals('e', cs.charAt(1));
        Assertions.assertEquals('o', cs.charAt(4));
    }

    @Test
    public void testToString() {
        final byte[] data = "World".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals("World", cs.toString());
    }

    @Test
    public void testToStringPartialLength() {
        final byte[] data = "Hello World".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(5);

        Assertions.assertEquals("Hello", cs.toString());
    }

    @Test
    public void testBytesReturnsUnderlyingArray() {
        final byte[] data = {65, 66, 67};
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        Assertions.assertSame(data, cs.bytes());
    }

    @Test
    public void testConstructorWithSize() {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(32);
        Assertions.assertEquals(32, cs.bytes().length);
        Assertions.assertEquals(0, cs.length());
    }
}
