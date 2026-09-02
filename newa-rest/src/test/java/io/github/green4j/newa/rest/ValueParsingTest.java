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

package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ValueParsingTest {

    @Test
    public void testTrueValues() {
        for (final String v
                : new String[]{
                    "true", "TRUE", "True", "tRuE",
                    "yes", "YES", "Yes", "y", "Y", "1"}) {
            Assertions.assertTrue(
                    ValueParsing.parseBoolean(v),
                    "Expected true for: " + v);
        }
    }

    @Test
    public void testFalseValues() {
        for (final String v
                : new String[]{
                    "false", "FALSE", "no", "NO",
                    "n", "0", "abc", "2", ""}) {
            Assertions.assertFalse(
                    ValueParsing.parseBoolean(v),
                    "Expected false for: " + v);
        }
    }

    @Test
    public void testWhitespace() {
        Assertions.assertTrue(
                ValueParsing.parseBoolean("  true  "));
        Assertions.assertTrue(
                ValueParsing.parseBoolean(" 1 "));
        Assertions.assertFalse(
                ValueParsing.parseBoolean("  false  "));
    }
}
