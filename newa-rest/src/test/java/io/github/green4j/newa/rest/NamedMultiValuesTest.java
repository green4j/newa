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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class NamedMultiValuesTest {

    private static NamedMultiValues of(
            final String name, final String... values) {
        final Map<String, List<String>> map =
                new LinkedHashMap<>();
        final List<String> list = new ArrayList<>();
        for (final String v : values) {
            list.add(v);
        }
        map.put(name, list);
        return new MapNamedMultiValues(map);
    }

    @Test
    public void testNumberOfValues() {
        final NamedMultiValues mv = of("ids", "1", "2", "3");
        Assertions.assertEquals(3, mv.numberOfValues("ids"));
        Assertions.assertEquals(0, mv.numberOfValues("missing"));
    }

    @Test
    public void testValueByIndex() {
        final NamedMultiValues mv = of("ids", "a", "b", "c");
        Assertions.assertEquals("a", mv.value("ids", 0));
        Assertions.assertEquals("b", mv.value("ids", 1));
        Assertions.assertEquals("c", mv.value("ids", 2));
        Assertions.assertNull(mv.value("ids", 3));
        Assertions.assertNull(mv.value("missing", 0));
    }

    @Test
    public void testValueRequired() throws BadRequestException {
        final NamedMultiValues mv = of("ids", "x");
        Assertions.assertEquals("x",
                mv.valueRequired("ids", 0));
        Assertions.assertThrows(BadRequestException.class,
                () -> mv.valueRequired("ids", 1));
        Assertions.assertThrows(BadRequestException.class,
                () -> mv.valueRequired("missing", 0));
    }

    @Test
    public void testIntConversionByIndex()
            throws BadRequestException {
        final NamedMultiValues mv = of("nums", "10", "20");
        Assertions.assertEquals(10,
                mv.valueRequiredAsInt("nums", 0));
        Assertions.assertEquals(20,
                mv.valueRequiredAsInt("nums", 1));
        Assertions.assertEquals(99,
                mv.valueAsInt("nums", 5, 99));
        Assertions.assertEquals(99,
                mv.valueAsInt("missing", 0, 99));
    }

    @Test
    public void testIntInvalidByIndex() {
        final NamedMultiValues mv = of("nums", "abc");
        Assertions.assertThrows(BadRequestException.class,
                () -> mv.valueRequiredAsInt("nums", 0));
    }

    @Test
    public void testBooleanByIndex() throws BadRequestException {
        final NamedMultiValues mv =
                of("flags", "yes", "false", "1");
        Assertions.assertTrue(
                mv.valueRequiredAsBoolean("flags", 0));
        Assertions.assertFalse(
                mv.valueRequiredAsBoolean("flags", 1));
        Assertions.assertTrue(
                mv.valueRequiredAsBoolean("flags", 2));
        Assertions.assertFalse(
                mv.valueAsBoolean("flags", 5, false));
    }

    @Test
    public void testBigDecimalByIndex()
            throws BadRequestException {
        final NamedMultiValues mv = of("prices", "9.99");
        Assertions.assertEquals(new BigDecimal("9.99"),
                mv.valueRequiredAsBigDecimal("prices", 0));
        Assertions.assertEquals(BigDecimal.ZERO,
                mv.valueAsBigDecimal("prices", 1, BigDecimal.ZERO));
    }

    @Test
    public void testValueWithDefault() {
        final NamedMultiValues mv = of("k", "v");
        Assertions.assertEquals("v",
                mv.value("k", 0, "fallback"));
        Assertions.assertEquals("fallback",
                mv.value("k", 5, "fallback"));
        Assertions.assertEquals("fallback",
                mv.value("missing", 0, "fallback"));
    }

    private static final class MapNamedMultiValues
            implements NamedMultiValues {
        private final List<String> names;
        private final List<List<String>> valueLists;

        private MapNamedMultiValues(
                final Map<String, List<String>> map) {
            names = new ArrayList<>(map.keySet());
            valueLists = new ArrayList<>(map.values());
        }

        @Override
        public int numberOfNames() {
            return names.size();
        }

        @Override
        public int nameToIndex(final String name) {
            return names.indexOf(name);
        }

        @Override
        public String indexToName(final int nameIndex) {
            if (nameIndex < 0
                    || nameIndex >= names.size()) {
                return null;
            }
            return names.get(nameIndex);
        }

        @Override
        public String value(final int nameIndex) {
            if (nameIndex < 0
                    || nameIndex >= valueLists.size()) {
                return null;
            }
            final List<String> vals =
                    valueLists.get(nameIndex);
            return vals.isEmpty() ? null : vals.get(0);
        }

        @Override
        public String value(final String name) {
            final int idx = nameToIndex(name);
            return idx == -1 ? null : value(idx);
        }

        @Override
        public int numberOfValues(final int nameIndex) {
            if (nameIndex < 0
                    || nameIndex >= valueLists.size()) {
                return 0;
            }
            return valueLists.get(nameIndex).size();
        }

        @Override
        public int numberOfValues(final String name) {
            final int idx = nameToIndex(name);
            return idx == -1 ? 0 : numberOfValues(idx);
        }

        @Override
        public String value(final int nameIndex,
                            final int valueIndex) {
            if (nameIndex < 0
                    || nameIndex >= valueLists.size()) {
                return null;
            }
            final List<String> vals =
                    valueLists.get(nameIndex);
            if (valueIndex < 0
                    || valueIndex >= vals.size()) {
                return null;
            }
            return vals.get(valueIndex);
        }

        @Override
        public String value(final String name,
                            final int valueIndex) {
            final int idx = nameToIndex(name);
            return idx == -1 ? null : value(idx, valueIndex);
        }
    }
}
