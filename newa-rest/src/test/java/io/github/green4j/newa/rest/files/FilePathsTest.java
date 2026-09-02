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

package io.github.green4j.newa.rest.files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

    @Test
    public void testSlashesAroundTheTailAreDropped() {
        Assertions.assertEquals("img/logo.png", decode("/img/logo.png"));
        Assertions.assertEquals("img", decode("/img/"));
        Assertions.assertEquals("", decode("/"));
        Assertions.assertEquals("", decode(""));
    }

    @Test
    public void testPercentEscapesAreDecoded() {
        Assertions.assertEquals("a file.txt", decode("/a%20file.txt"));
        Assertions.assertEquals("a+b.txt", decode("/a+b.txt"), "a plus is a plus in a path, not a space");
        Assertions.assertEquals(CYRILLIC, decode(CYRILLIC_ENCODED),
                "the bytes went out as UTF-8 and have to come back as UTF-8");
    }

    @Test
    public void testMalformedEscapesAreRefused() {
        Assertions.assertNull(decode("/a%2"));
        Assertions.assertNull(decode("/a%zz.txt"));
        Assertions.assertNull(decode("/a%"));
    }

    @Test
    public void testTooLongIsRefused() {
        final StringBuilder tail = new StringBuilder("/");
        for (int i = 0; i < buffer.length + 1; i++) {
            tail.append('a');
        }
        Assertions.assertNull(decode(tail.toString()));
    }

    @Test
    public void testWhatMayBeResolved() {
        Assertions.assertTrue(FilePaths.isSafe("img/logo.png"));
        Assertions.assertTrue(FilePaths.isSafe("a.txt"));
        Assertions.assertTrue(FilePaths.isSafe("...a"));
        Assertions.assertTrue(FilePaths.isSafe("a file.txt"));
        Assertions.assertTrue(FilePaths.isSafe(CYRILLIC));
    }

    @Test
    public void testWhatMayNot() {
        Assertions.assertFalse(FilePaths.isSafe(""));
        Assertions.assertFalse(FilePaths.isSafe(".."));
        Assertions.assertFalse(FilePaths.isSafe("../etc/passwd"));
        Assertions.assertFalse(FilePaths.isSafe("img/../../etc/passwd"));
        Assertions.assertFalse(FilePaths.isSafe("img/./logo.png"));
        Assertions.assertFalse(FilePaths.isSafe("img//logo.png"));
        Assertions.assertFalse(FilePaths.isSafe("/etc/passwd"));
        Assertions.assertFalse(FilePaths.isSafe("img\\logo.png"));
        Assertions.assertFalse(FilePaths.isSafe("logo.png\u0000.txt"),
                "a NUL is where the name ends as far as the OS is concerned");
    }

    @Test
    public void testAnEncodedDotDotIsDecodedBeforeItIsJudged() {
        final String decoded = decode("/%2e%2e/etc/passwd");
        Assertions.assertEquals("../etc/passwd", decoded);
        Assertions.assertFalse(FilePaths.isSafe(decoded));
    }
}
