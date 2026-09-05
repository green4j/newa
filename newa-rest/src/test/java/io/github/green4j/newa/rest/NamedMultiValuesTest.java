/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reading one of several values under a name. The conversions themselves are the ones
 * {@link NamedValuesTest} covers; what is asked here is that the index reaches the right value and that
 * an index past the end is the same as a name which is not there.
 */
class NamedMultiValuesTest {

    private static NamedMultiValues of(final String name,
                                       final String... values) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        final List<String> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(values[i]);
        }
        map.put(name, list);
        return new MapNamedMultiValues(map);
    }

    /** One way of reading a value out, so that a case can name the forms every conversion has. */
    @FunctionalInterface
    private interface Reader {
        Object read(NamedMultiValues mv) throws BadRequestException;
    }

    @Test
    public void testNumberOfValues() {
        final NamedMultiValues mv = of("ids", "1", "2", "3");
        Assertions.assertEquals(3, mv.numberOfValues("ids"));
        Assertions.assertEquals(0, mv.numberOfValues("missing"));
    }

    @Test
    public void theValueAtAnIndexIsTheOneThere() throws BadRequestException {
        final NamedMultiValues mv = of("ids", "a", "b", "c");

        Assertions.assertEquals("a", mv.value("ids", 0));
        Assertions.assertEquals("b", mv.value("ids", 1));
        Assertions.assertEquals("c", mv.value("ids", 2));
        Assertions.assertNull(mv.value("ids", 3), "an index past the end names nothing");
        Assertions.assertNull(mv.value("missing", 0));

        Assertions.assertEquals("a", mv.valueRequired("ids", 0));
        Assertions.assertThrows(BadRequestException.class, () -> mv.valueRequired("ids", 3));
        Assertions.assertThrows(BadRequestException.class, () -> mv.valueRequired("missing", 0));

        Assertions.assertEquals("a", mv.value("ids", 0, "fallback"));
        Assertions.assertEquals("fallback", mv.value("ids", 5, "fallback"));
        Assertions.assertEquals("fallback", mv.value("missing", 0, "fallback"));
    }

    /**
     * @return one case per type: what it is called, the values under the name, what the value at index 0
     *         converts to, how it is asked for, how an index past the end is asked for, and what that
     *         falls back to.
     */
    private static Stream<Arguments> everyConversionByIndex() {
        return Stream.of(
                Arguments.of("int", new String[]{"10", "20"}, 10,
                        (Reader) mv -> mv.valueRequiredAsInt("vals", 0),
                        (Reader) mv -> mv.valueAsInt("vals", 5, 99), 99),
                Arguments.of("int at the second index", new String[]{"10", "20"}, 20,
                        (Reader) mv -> mv.valueRequiredAsInt("vals", 1),
                        (Reader) mv -> mv.valueAsInt("vals", 5, 99), 99),
                Arguments.of("boolean", new String[]{"yes", "false"}, true,
                        (Reader) mv -> mv.valueRequiredAsBoolean("vals", 0),
                        (Reader) mv -> mv.valueAsBoolean("vals", 5, false), false),
                Arguments.of("boolean at the second index", new String[]{"yes", "false"}, false,
                        (Reader) mv -> mv.valueRequiredAsBoolean("vals", 1),
                        (Reader) mv -> mv.valueAsBoolean("vals", 5, false), false),
                Arguments.of("BigDecimal", new String[]{"9.99"}, new BigDecimal("9.99"),
                        (Reader) mv -> mv.valueRequiredAsBigDecimal("vals", 0),
                        (Reader) mv -> mv.valueAsBigDecimal("vals", 1, BigDecimal.ZERO),
                        BigDecimal.ZERO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyConversionByIndex")
    public void convertsTheValueAtAnIndex(final String type,
                                          final String[] values,
                                          final Object expected,
                                          final Reader at,
                                          final Reader pastTheEnd,
                                          final Object fallback) throws BadRequestException {
        final NamedMultiValues mv = of("vals", values);

        Assertions.assertEquals(expected, at.read(mv), type);
        Assertions.assertEquals(fallback, pastTheEnd.read(mv), type + " past the end of the values");
    }

    @Test
    public void whatIsNotAValueOfThatTypeIsABadRequest() {
        final NamedMultiValues mv = of("vals", "abc");

        Assertions.assertThrows(BadRequestException.class, () -> mv.valueRequiredAsInt("vals", 0));
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
