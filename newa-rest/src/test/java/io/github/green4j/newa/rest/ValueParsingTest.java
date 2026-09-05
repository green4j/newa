/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The words which mean true, and everything else. This is the one table of them; what reads a boolean out
 * of a request is asked only that it comes here, in {@link NamedValuesTest}.
 */
class ValueParsingTest {

    @ParameterizedTest
    @CsvSource({
        "true,      true",
        "TRUE,      true",
        "True,      true",
        "tRuE,      true",
        "yes,       true",
        "YES,       true",
        "Yes,       true",
        "y,         true",
        "Y,         true",
        "1,         true",
        "false,     false",
        "FALSE,     false",
        "no,        false",
        "NO,        false",
        "n,         false",
        "0,         false",
        "abc,       false",  // anything which is not one of the words is simply not true
        "2,         false",
        "'',        false",
        // and what is asked is the word, not the spaces around it
        "'  true  ', true",
        "' 1 ',      true",
        "'  false  ', false"
    })
    public void parseBoolean(final String value,
                             final boolean expected) {
        Assertions.assertEquals(expected, ValueParsing.parseBoolean(value), "for: [" + value + "]");
    }
}
