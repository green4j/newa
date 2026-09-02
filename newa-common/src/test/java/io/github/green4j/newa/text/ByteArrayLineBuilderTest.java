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

import io.github.green4j.jelly.AsciiByteArrayWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ByteArrayLineBuilderTest {

    private static ByteArrayLineBuilder builder() {
        return new ByteArrayLineBuilder(
                new AsciiByteArrayWriter(256));
    }

    @Test
    public void testAppendAndToString() {
        final ByteArrayLineBuilder b = builder();
        b.append("hello");
        Assertions.assertEquals("hello", b.toString());
    }

    @Test
    public void testAppendln() {
        final ByteArrayLineBuilder b = builder();
        b.appendln("line1");
        Assertions.assertEquals(
                "line1" + ByteArrayLineBuilder.NL,
                b.toString());
    }

    @Test
    public void testAppendlnChar() {
        final ByteArrayLineBuilder b = builder();
        b.appendln('x');
        Assertions.assertEquals(
                "x" + ByteArrayLineBuilder.NL,
                b.toString());
    }

    @Test
    public void testTabCachedLevel() {
        final ByteArrayLineBuilder b = builder();
        b.tab(2);
        b.append("text");
        Assertions.assertEquals(
                "        text", b.toString());
    }

    @Test
    public void testTabLargeLevel() {
        final ByteArrayLineBuilder b = builder();
        b.tab(12);
        b.append("x");
        final String result = b.toString();
        final int expectedSpaces = 12 * 4;
        Assertions.assertEquals(expectedSpaces + 1,
                result.length());
        Assertions.assertTrue(result.endsWith("x"));
    }

    @Test
    public void testTabCustomSize() {
        final ByteArrayLineBuilder b = builder();
        b.tab(2, 2);
        b.append("x");
        Assertions.assertEquals("    x", b.toString());
    }

    @Test
    public void testClear() {
        final ByteArrayLineBuilder b = builder();
        b.append("data");
        Assertions.assertFalse(b.toString().isEmpty());
        b.clear();
        Assertions.assertEquals("", b.toString());
    }

    @Test
    public void testAppendChar() {
        final ByteArrayLineBuilder b = builder();
        b.append('Z');
        Assertions.assertEquals("Z", b.toString());
    }
}
