package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ValueParsingTest {

    @Test
    public void testTrueValues() {
        for (final String v
                : new String[]{
                    "true", "TRUE", "True", "tRuE",
                    "yes", "YES", "Yes", "y", "Y", "1"}) {
            Assertions.assertTrue(
                    ValueParsing.parseBoolean(v),
                    "Expected true for: " + v);
        }
    }

    @Test
    public void testFalseValues() {
        for (final String v
                : new String[]{
                    "false", "FALSE", "no", "NO",
                    "n", "0", "abc", "2", ""}) {
            Assertions.assertFalse(
                    ValueParsing.parseBoolean(v),
                    "Expected false for: " + v);
        }
    }

    @Test
    public void testWhitespace() {
        Assertions.assertTrue(
                ValueParsing.parseBoolean("  true  "));
        Assertions.assertTrue(
                ValueParsing.parseBoolean(" 1 "));
        Assertions.assertFalse(
                ValueParsing.parseBoolean("  false  "));
    }
}
