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

public interface NamedValues {
    int numberOfNames();

    int nameToIndex(String name);

    String indexToName(int nameIndex);

    String value(int nameIndex);

    String value(String name);

    default String value(final int nameIndex,
                         final String defaultValue) {
        final String v = value(nameIndex);
        return v == null ? defaultValue : v;
    }

    default String value(final String name,
                         final String defaultValue) {
        final String v = value(name);
        return v == null ? defaultValue : v;
    }

    default String valueRequired(String name)
            throws BadRequestException {
        final String v = value(name, null);
        if (v == null) {
            throw new BadRequestException("'" + name + "' is required");
        }
        return v;
    }

    default byte valueAsByte(final String name,
                             final byte defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default byte valueRequiredAsByte(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Byte.parseByte(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid byte: " + v);
        }
    }

    default short valueAsShort(final String name,
                               final short defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default short valueRequiredAsShort(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Short.parseShort(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid short: " + v);
        }
    }

    default int valueAsInt(final String name,
                           final int defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default int valueRequiredAsInt(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Integer.parseInt(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid int: " + v);
        }
    }

    default long valueAsLong(final String name,
                             final long defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default long valueRequiredAsLong(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Long.parseLong(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid long: " + v);
        }
    }

    default float valueAsFloat(final String name,
                               final float defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default float valueRequiredAsFloat(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Float.parseFloat(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid float: " + v);
        }
    }

    default double valueAsDouble(final String name,
                                 final double defaultValue)
            throws BadRequestException {
        final String v = value(name);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid double: " + v);
        }
    }

    default double valueRequiredAsDouble(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return Double.parseDouble(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name + "' is not a valid double: " + v);
        }
    }

    default BigDecimal valueAsBigDecimal(final String name,
                                         final BigDecimal defaultValue)
            throws BadRequestException {
        final String v = value(name);
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

    default BigDecimal valueRequiredAsBigDecimal(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        try {
            return new BigDecimal(v);
        } catch (final NumberFormatException e) {
            throw new BadRequestException(
                    "'" + name
                            + "' is not a valid BigDecimal: " + v);
        }
    }

    default boolean valueAsBoolean(final String name,
                                   final boolean defaultValue) {
        final String v = value(name);
        if (v == null) {
            return defaultValue;
        }
        return ValueParsing.parseBoolean(v);
    }

    default boolean valueRequiredAsBoolean(final String name)
            throws BadRequestException {
        final String v = valueRequired(name);
        return ValueParsing.parseBoolean(v);
    }
}
