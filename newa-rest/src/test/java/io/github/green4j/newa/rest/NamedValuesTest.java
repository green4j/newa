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
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

class NamedValuesTest {

    private static NamedValues of(final String... kvPairs) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put(kvPairs[i], kvPairs[i + 1]);
        }
        return new MapNamedValues(map);
    }

    /** One way of reading a value out, so that a case can name the three forms every conversion has. */
    @FunctionalInterface
    private interface Reader {
        Object read(NamedValues nv) throws BadRequestException;
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
        Assertions.assertThrows(BadRequestException.class, () -> nv.valueRequired("missing"));
    }

    /**
     * Every conversion, in all three of the forms it comes in: required, defaulted with the value there,
     * and defaulted with nothing under the name.
     *
     * @return one case per type: what it is called, the text to convert, what that converts to, the three
     *         ways of asking for it, and what the last of them falls back to.
     */
    private static Stream<Arguments> everyConversion() {
        return Stream.of(
                Arguments.of("byte", "42", (byte) 42,
                        (Reader) nv -> nv.valueRequiredAsByte("val"),
                        (Reader) nv -> nv.valueAsByte("val", (byte) 0),
                        (Reader) nv -> nv.valueAsByte("missing", (byte) 7), (byte) 7),
                Arguments.of("short", "1000", (short) 1000,
                        (Reader) nv -> nv.valueRequiredAsShort("val"),
                        (Reader) nv -> nv.valueAsShort("val", (short) 0),
                        (Reader) nv -> nv.valueAsShort("missing", (short) 5), (short) 5),
                Arguments.of("int", "123456", 123456,
                        (Reader) nv -> nv.valueRequiredAsInt("val"),
                        (Reader) nv -> nv.valueAsInt("val", 0),
                        (Reader) nv -> nv.valueAsInt("missing", 99), 99),
                Arguments.of("long", "9999999999", 9999999999L,
                        (Reader) nv -> nv.valueRequiredAsLong("val"),
                        (Reader) nv -> nv.valueAsLong("val", 0L),
                        (Reader) nv -> nv.valueAsLong("missing", 1L), 1L),
                Arguments.of("float", "3.14", 3.14f,
                        (Reader) nv -> nv.valueRequiredAsFloat("val"),
                        (Reader) nv -> nv.valueAsFloat("val", 0f),
                        (Reader) nv -> nv.valueAsFloat("missing", 1.0f), 1.0f),
                Arguments.of("double", "2.718281828", 2.718281828,
                        (Reader) nv -> nv.valueRequiredAsDouble("val"),
                        (Reader) nv -> nv.valueAsDouble("val", 0.0),
                        (Reader) nv -> nv.valueAsDouble("missing", 0.5), 0.5),
                Arguments.of("BigDecimal", "123.456789", new BigDecimal("123.456789"),
                        (Reader) nv -> nv.valueRequiredAsBigDecimal("val"),
                        (Reader) nv -> nv.valueAsBigDecimal("val", BigDecimal.ZERO),
                        (Reader) nv -> nv.valueAsBigDecimal("missing", BigDecimal.ZERO), BigDecimal.ZERO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyConversion")
    public void convertsTheValue(final String type,
                                 final String raw,
                                 final Object expected,
                                 final Reader required,
                                 final Reader defaulted,
                                 final Reader missing,
                                 final Object fallback) throws BadRequestException {
        final NamedValues nv = of("val", raw);

        Assertions.assertEquals(expected, required.read(nv), type);
        Assertions.assertEquals(expected, defaulted.read(nv), type);
        Assertions.assertEquals(fallback, missing.read(nv), type + " under a name which is not there");
    }

    /**
     * What is not a number of that type at all.
     *
     * @return one case per type: what it is called, the text which is not one, and how it is asked for.
     */
    private static Stream<Arguments> everyConversionOfSomethingElse() {
        return Stream.of(
                Arguments.of("byte", "abc", (Reader) nv -> nv.valueRequiredAsByte("val")),
                Arguments.of("int", "not_a_number", (Reader) nv -> nv.valueRequiredAsInt("val")),
                Arguments.of("int, asked for with a default", "not_a_number",
                        (Reader) nv -> nv.valueAsInt("val", 0)),
                Arguments.of("double", "xyz", (Reader) nv -> nv.valueRequiredAsDouble("val")),
                Arguments.of("BigDecimal", "not_decimal",
                        (Reader) nv -> nv.valueRequiredAsBigDecimal("val")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyConversionOfSomethingElse")
    public void whatIsNotAValueOfThatTypeIsABadRequest(final String type,
                                                       final String raw,
                                                       final Reader reader) {
        final NamedValues nv = of("val", raw);

        Assertions.assertThrows(BadRequestException.class, () -> reader.read(nv), type);
    }

    /**
     * @return one case per required conversion, each asked of a map which has nothing under the name.
     */
    private static Stream<Arguments> everyRequiredConversion() {
        return Stream.of(
                Arguments.of("byte", (Reader) nv -> nv.valueRequiredAsByte("val")),
                Arguments.of("boolean", (Reader) nv -> nv.valueRequiredAsBoolean("missing")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRequiredConversion")
    public void aRequiredValueWhichIsNotThereIsABadRequest(final String type,
                                                           final Reader reader) {
        final NamedValues nv = of();

        Assertions.assertThrows(BadRequestException.class, () -> reader.read(nv), type);
    }

    /**
     * The value table itself belongs to {@link ValueParsingTest}; what is asked here is only that
     * NamedValues reads a boolean the same way.
     *
     * @param trueValue one of the words which mean true.
     */
    @ParameterizedTest
    @ValueSource(strings = {"true", "yes", "1"})
    public void readsABooleanTheSameWayValueParsingDoes(final String trueValue)
            throws BadRequestException {
        final NamedValues nv = of("flag", trueValue);

        Assertions.assertTrue(nv.valueRequiredAsBoolean("flag"), trueValue);
        Assertions.assertTrue(nv.valueAsBoolean("flag", false), trueValue);
        Assertions.assertFalse(of("flag", "no").valueRequiredAsBoolean("flag"));
    }

    @Test
    public void testBooleanDefault() {
        final NamedValues nv = of();
        Assertions.assertTrue(nv.valueAsBoolean("missing", true));
        Assertions.assertFalse(nv.valueAsBoolean("missing", false));
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
