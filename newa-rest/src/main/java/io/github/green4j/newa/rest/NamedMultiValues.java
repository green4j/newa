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

import java.math.BigDecimal;

public interface NamedMultiValues extends NamedValues {
    int numberOfValues(int nameIndex);

    int numberOfValues(String name);

    String value(int nameIndex,
                 int valueIndex);

    String value(String name,
                 int valueIndex);

    default String value(final int nameIndex,
                         final int valueIndex,
                         final String defaultValue) {
        final String v = value(nameIndex, valueIndex);
        return v == null ? defaultValue : v;
    }

    default String value(final String name,
                         final int valueIndex,
                         final String defaultValue) {
        final String v = value(name, valueIndex);
        return v == null ? defaultValue : v;
    }

    default String valueRequired(final String name,
                                 final int valueIndex)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            throw new BadRequestException("'" + name + "' is required");
        }
        return v;
    }

    default byte valueAsByte(final String name,
                             final int valueIndex,
                             final byte defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Byte.parseByte(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid byte: " + v);
        }
    }

    default byte valueRequiredAsByte(final String name,
                                     final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Byte.parseByte(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid byte: " + v);
        }
    }

    default short valueAsShort(final String name,
                               final int valueIndex,
                               final short defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Short.parseShort(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid short: " + v);
        }
    }

    default short valueRequiredAsShort(final String name,
                                       final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Short.parseShort(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid short: " + v);
        }
    }

    default int valueAsInt(final String name,
                           final int valueIndex,
                           final int defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid int: " + v);
        }
    }

    default int valueRequiredAsInt(final String name,
                                   final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Integer.parseInt(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid int: " + v);
        }
    }

    default long valueAsLong(final String name,
                             final int valueIndex,
                             final long defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid long: " + v);
        }
    }

    default long valueRequiredAsLong(final String name,
                                     final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Long.parseLong(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid long: " + v);
        }
    }

    default float valueAsFloat(final String name,
                               final int valueIndex,
                               final float defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid float: " + v);
        }
    }

    default float valueRequiredAsFloat(final String name,
                                       final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Float.parseFloat(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid float: " + v);
        }
    }

    default double valueAsDouble(final String name,
                                 final int valueIndex,
                                 final double defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name
                            + "' is not a valid double: " + v);
        }
    }

    default double valueRequiredAsDouble(final String name,
                                         final int valueIndex)
            throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return Double.parseDouble(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name
                            + "' is not a valid double: " + v);
        }
    }

    default BigDecimal valueAsBigDecimal(final String name,
                                         final int valueIndex,
                                         final BigDecimal defaultValue)
            throws BadRequestException {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name
                            + "' is not a valid BigDecimal: " + v);
        }
    }

    default BigDecimal valueRequiredAsBigDecimal(
            final String name,
            final int valueIndex) throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        try {
            return new BigDecimal(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name
                            + "' is not a valid BigDecimal: " + v);
        }
    }

    default boolean valueAsBoolean(final String name,
                                   final int valueIndex,
                                   final boolean defaultValue) {
        final String v = value(name, valueIndex);
        if (v == null) {
            return defaultValue;
        }
        return ValueParsing.parseBoolean(v);
    }

    default boolean valueRequiredAsBoolean(
            final String name,
            final int valueIndex) throws BadRequestException {
        final String v = valueRequired(name, valueIndex);
        return ValueParsing.parseBoolean(v);
    }
}
