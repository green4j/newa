/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the harness answers on its admin port: the budget's gauges, its refusal counters by reason, and what
 * the JVM says about its own memory, one {@code key=value} to a line.
 */
final class BudgetReport {
    private final Map<String, String> values = new LinkedHashMap<>();
    private final String raw;

    BudgetReport(final String raw) {
        this.raw = raw;
        for (final String line : raw.split("\n")) {
            final int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
    }

    boolean budgeted() {
        return Boolean.parseBoolean(values.get("budget"));
    }

    long value(final String key) {
        final String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("The harness reported no " + key + ":\n" + raw);
        }
        return Long.parseLong(value);
    }

    /**
     * @return every refusal the budget has made, whatever the reason
     */
    long refused() {
        return value("refused");
    }

    /**
     * @return the refusals made because one of the two byte capacities was full, which are the only ones a
     *         test about memory is asking after: a connection limit refuses without consulting the bytes
     */
    long refusedForMemory() {
        return value("refused.HEAP")
                + value("refused.DIRECT_MEMORY")
                + value("refused.HEAP_AND_DIRECT_MEMORY");
    }

    @Override
    public String toString() {
        return raw;
    }
}
