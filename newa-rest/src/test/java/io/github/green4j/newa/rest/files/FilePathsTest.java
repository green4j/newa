/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

class FilePathsTest {
    // "privet.txt" written in Cyrillic, and the same name percent-encoded as the UTF-8 bytes of it
    private static final String CYRILLIC = "\u043f\u0440\u0438\u0432\u0435\u0442.txt";
    private static final String CYRILLIC_ENCODED = "/%d0%bf%d1%80%d0%b8%d0%b2%d0%b5%d1%82.txt";

    private final byte[] buffer = new byte[256];

    private String decode(final String tail) {
        final int length = FilePaths.decode(tail, 0, tail.length(), buffer);
        return length < 0 ? null : new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @CsvSource({
        // the slashes around the tail are not part of the name
        "/img/logo.png,  img/logo.png",
        "/img/,          img",
        "/,              ''",
        "'',             ''",
        // and a percent escape is the byte it names
        "/a%20file.txt,  a file.txt",
        "/a+b.txt,       a+b.txt" // a plus is a plus in a path, not a space
    })
    public void decodesTheTail(final String tail,
                               final String expected) {
        Assertions.assertEquals(expected, decode(tail), tail);
    }

    @Test
    public void theBytesWentOutAsUtf8AndComeBackAsUtf8() {
        Assertions.assertEquals(CYRILLIC, decode(CYRILLIC_ENCODED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a%2", "/a%zz.txt", "/a%"})
    public void aMalformedEscapeIsRefused(final String tail) {
        Assertions.assertNull(decode(tail), tail);
    }

    @Test
    public void testTooLongIsRefused() {
        final StringBuilder tail = new StringBuilder("/");
        for (int i = 0; i < buffer.length + 1; i++) {
            tail.append('a');
        }
        Assertions.assertNull(decode(tail.toString()));
    }

    @ParameterizedTest
    @CsvSource({
        "img/logo.png,           true",
        "a.txt,                  true",
        "...a,                   true",  // dots which are not the dots
        "a file.txt,             true",
        "'',                     false",
        "..,                     false",
        "../etc/passwd,          false",
        "img/../../etc/passwd,   false",
        "img/./logo.png,         false",
        "img//logo.png,          false",
        "/etc/passwd,            false",
        "img\\logo.png,          false"
    })
    public void isSafe(final String path,
                       final boolean expected) {
        Assertions.assertEquals(expected, FilePaths.isSafe(path), path);
    }

    @Test
    public void aNameNoFileSystemCouldCarryIsRefused() {
        // a NUL is where the name ends as far as the OS is concerned
        Assertions.assertFalse(FilePaths.isSafe("logo.png\u0000.txt"));
        // while a name which is simply not ASCII is a name like any other
        Assertions.assertTrue(FilePaths.isSafe(CYRILLIC));
    }

    @Test
    public void testAnEncodedDotDotIsDecodedBeforeItIsJudged() {
        final String decoded = decode("/%2e%2e/etc/passwd");
        Assertions.assertEquals("../etc/passwd", decoded);
        Assertions.assertFalse(FilePaths.isSafe(decoded));
    }
}
