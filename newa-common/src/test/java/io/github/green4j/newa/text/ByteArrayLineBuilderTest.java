/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ByteArrayLineBuilderTest {

    private static ByteArrayLineBuilder builder() {
        return new ByteArrayLineBuilder(new AsciiByteArrayWriter(256));
    }

    /**
     * @param appended  the text to append.
     * @param asOneChar whether to reach the char overload rather than the CharSequence one.
     */
    @ParameterizedTest
    @CsvSource({"hello, false", "Z, true"})
    public void appendsWhatItIsGiven(final String appended,
                                     final boolean asOneChar) {
        final ByteArrayLineBuilder b = builder();
        if (asOneChar) {
            b.append(appended.charAt(0));
        } else {
            b.append(appended);
        }
        Assertions.assertEquals(appended, b.toString());
    }

    @ParameterizedTest
    @CsvSource({"line1, false", "x, true"})
    public void appendlnEndsTheLine(final String appended,
                                    final boolean asOneChar) {
        final ByteArrayLineBuilder b = builder();
        if (asOneChar) {
            b.appendln(appended.charAt(0));
        } else {
            b.appendln(appended);
        }
        Assertions.assertEquals(appended + ByteArrayLineBuilder.NL, b.toString());
    }

    /**
     * @param level  to indent by.
     * @param size   of one level, or -1 to ask for the default one.
     * @param spaces the indent is expected to be.
     */
    @ParameterizedTest
    @CsvSource({
        "2,  -1, 8",  // the default size, out of the cache of prepared indents
        "12, -1, 48", // past the end of that cache, which has to build one instead
        "2,  2,  4"   // and a size of the caller's own
    })
    public void tabIndentsBySoManySpaces(final int level,
                                         final int size,
                                         final int spaces) {
        final ByteArrayLineBuilder b = builder();
        if (size < 0) {
            b.tab(level);
        } else {
            b.tab(level, size);
        }
        b.append('x');

        final StringBuilder expected = new StringBuilder();
        for (int i = 0; i < spaces; i++) {
            expected.append(' ');
        }
        Assertions.assertEquals(expected.append('x').toString(), b.toString());
    }

    @Test
    public void testClear() {
        final ByteArrayLineBuilder b = builder();
        b.append("data");
        Assertions.assertFalse(b.toString().isEmpty());
        b.clear();
        Assertions.assertEquals("", b.toString());
    }
}
