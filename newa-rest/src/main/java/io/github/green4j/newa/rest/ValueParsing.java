/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

final class ValueParsing {
    private ValueParsing() {
    }

    static boolean parseBoolean(final CharSequence cs) {
        final String s = cs.toString().trim();
        return "true".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s)
                || "1".equals(s);
    }
}
