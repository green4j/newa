/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import java.math.BigDecimal;

/**
 * Named strings read without building a map: the path parameters, the headers, a form. A name is looked up
 * either by its {@link String} or by the index {@link #nameToIndex(String)} answers, which is what a handler
 * reading the same name on every request uses.
 * <p>
 * The typed readers - {@code valueAsInt}, {@code valueAsLong}, {@code valueAsBoolean} and the rest - come in
 * two forms: one with a default for a value which may be absent, and {@code valueRequired...} which throws
 * {@link BadRequestException}, and so answers the client {@code 400}, when it is not there. A value which is
 * there but cannot be parsed is a {@link BadRequestException} either way; a boolean reads
 * {@code true/yes/y/1}, case-insensitively.
 */
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
