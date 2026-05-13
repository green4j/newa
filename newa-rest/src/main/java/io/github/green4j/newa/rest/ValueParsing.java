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
