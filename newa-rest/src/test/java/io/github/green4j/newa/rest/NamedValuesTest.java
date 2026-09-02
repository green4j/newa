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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

class NamedValuesTest {

    private static NamedValues of(final String... kvPairs) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put(kvPairs[i], kvPairs[i + 1]);
        }
        return new MapNamedValues(map);
    }

    @Test
    public void testValueWithDefault() {
        final NamedValues nv = of("color", "blue");
        Assertions.assertEquals("blue", nv.value("color", "red"));
        Assertions.assertEquals("red", nv.value("missing", "red"));
    }

    @Test
    public void testValueRequired() throws BadRequestException {
        final NamedValues nv = of("color", "blue");
        Assertions.assertEquals("blue", nv.valueRequired("color"));
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequired("missing"));
    }

    @Test
    public void testByteConversion() throws BadRequestException {
        final NamedValues nv = of("val", "42");
        Assertions.assertEquals((byte) 42,
                nv.valueRequiredAsByte("val"));
        Assertions.assertEquals((byte) 42,
                nv.valueAsByte("val", (byte) 0));
        Assertions.assertEquals((byte) 7,
                nv.valueAsByte("missing", (byte) 7));
    }

    @Test
    public void testByteInvalid() {
        final NamedValues nv = of("val", "abc");
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsByte("val"));
    }

    @Test
    public void testByteRequiredMissing() {
        final NamedValues nv = of();
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsByte("val"));
    }

    @Test
    public void testShortConversion() throws BadRequestException {
        final NamedValues nv = of("val", "1000");
        Assertions.assertEquals((short) 1000,
                nv.valueRequiredAsShort("val"));
        Assertions.assertEquals((short) 1000,
                nv.valueAsShort("val", (short) 0));
        Assertions.assertEquals((short) 5,
                nv.valueAsShort("missing", (short) 5));
    }

    @Test
    public void testIntConversion() throws BadRequestException {
        final NamedValues nv = of("val", "123456");
        Assertions.assertEquals(123456, nv.valueRequiredAsInt("val"));
        Assertions.assertEquals(123456, nv.valueAsInt("val", 0));
        Assertions.assertEquals(99, nv.valueAsInt("missing", 99));
    }

    @Test
    public void testIntInvalid() {
        final NamedValues nv = of("val", "not_a_number");
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsInt("val"));
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueAsInt("val", 0));
    }

    @Test
    public void testLongConversion() throws BadRequestException {
        final NamedValues nv = of("val", "9999999999");
        Assertions.assertEquals(9999999999L,
                nv.valueRequiredAsLong("val"));
        Assertions.assertEquals(9999999999L,
                nv.valueAsLong("val", 0L));
        Assertions.assertEquals(1L,
                nv.valueAsLong("missing", 1L));
    }

    @Test
    public void testFloatConversion() throws BadRequestException {
        final NamedValues nv = of("val", "3.14");
        Assertions.assertEquals(3.14f,
                nv.valueRequiredAsFloat("val"), 0.001f);
        Assertions.assertEquals(3.14f,
                nv.valueAsFloat("val", 0f), 0.001f);
        Assertions.assertEquals(1.0f,
                nv.valueAsFloat("missing", 1.0f), 0.001f);
    }

    @Test
    public void testDoubleConversion() throws BadRequestException {
        final NamedValues nv = of("val", "2.718281828");
        Assertions.assertEquals(2.718281828,
                nv.valueRequiredAsDouble("val"), 0.0000001);
        Assertions.assertEquals(2.718281828,
                nv.valueAsDouble("val", 0.0), 0.0000001);
        Assertions.assertEquals(0.5,
                nv.valueAsDouble("missing", 0.5), 0.0000001);
    }

    @Test
    public void testDoubleInvalid() {
        final NamedValues nv = of("val", "xyz");
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsDouble("val"));
    }

    @Test
    public void testBigDecimalConversion()
            throws BadRequestException {
        final NamedValues nv = of("val", "123.456789");
        Assertions.assertEquals(new BigDecimal("123.456789"),
                nv.valueRequiredAsBigDecimal("val"));
        final BigDecimal fallback = BigDecimal.ZERO;
        Assertions.assertEquals(new BigDecimal("123.456789"),
                nv.valueAsBigDecimal("val", fallback));
        Assertions.assertEquals(fallback,
                nv.valueAsBigDecimal("missing", fallback));
    }

    @Test
    public void testBigDecimalInvalid() {
        final NamedValues nv = of("val", "not_decimal");
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsBigDecimal("val"));
    }

    @Test
    public void testBooleanTrueValues()
            throws BadRequestException {
        for (final String trueVal
                : new String[]{
                    "true", "TRUE", "True",
                    "yes", "YES", "y", "Y", "1"}) {
            final NamedValues nv = of("flag", trueVal);
            Assertions.assertTrue(
                    nv.valueRequiredAsBoolean("flag"),
                    "Expected true for: " + trueVal);
            Assertions.assertTrue(
                    nv.valueAsBoolean("flag", false),
                    "Expected true for: " + trueVal);
        }
    }

    @Test
    public void testBooleanFalseValues()
            throws BadRequestException {
        for (final String falseVal
                : new String[]{"false", "no", "0", "abc", ""}) {
            final NamedValues nv = of("flag", falseVal);
            Assertions.assertFalse(
                    nv.valueRequiredAsBoolean("flag"),
                    "Expected false for: " + falseVal);
        }
    }

    @Test
    public void testBooleanDefault() {
        final NamedValues nv = of();
        Assertions.assertTrue(
                nv.valueAsBoolean("missing", true));
        Assertions.assertFalse(
                nv.valueAsBoolean("missing", false));
    }

    @Test
    public void testBooleanRequiredMissing() {
        final NamedValues nv = of();
        Assertions.assertThrows(BadRequestException.class,
                () -> nv.valueRequiredAsBoolean("missing"));
    }

    private static final class MapNamedValues implements NamedValues {
        private final String[] keys;
        private final String[] vals;

        private MapNamedValues(final Map<String, String> map) {
            keys = new String[map.size()];
            vals = new String[map.size()];
            int i = 0;
            for (final Map.Entry<String, String> e
                    : map.entrySet()) {
                keys[i] = e.getKey();
                vals[i] = e.getValue();
                i++;
            }
        }

        @Override
        public int numberOfNames() {
            return keys.length;
        }

        @Override
        public int nameToIndex(final String name) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String indexToName(final int nameIndex) {
            if (nameIndex < 0 || nameIndex >= keys.length) {
                return null;
            }
            return keys[nameIndex];
        }

        @Override
        public String value(final int nameIndex) {
            if (nameIndex < 0 || nameIndex >= vals.length) {
                return null;
            }
            return vals[nameIndex];
        }

        @Override
        public String value(final String name) {
            final int idx = nameToIndex(name);
            return idx == -1 ? null : vals[idx];
        }
    }
}
