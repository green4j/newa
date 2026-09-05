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

import java.nio.file.Path;
import java.nio.file.Paths;

class PathMaskTest {
    private static final Path ANY = Paths.get("/tmp/newa-test-any");

    @ParameterizedTest(name = "{0} {2} {1}")
    @CsvSource({
        // a literal names one path and nothing around it
        "img/logo.png,   img/logo.png,       true",
        "img/logo.png,   img/logo.jpg,       false",
        "img/logo.png,   logo.png,           false",
        "img/logo.png,   a/img/logo.png,     false",
        // a star stays within one segment
        "img/*.png,      img/logo.png,       true",
        "img/*.png,      img/.png,           true",
        "img/*.png,      img/icons/logo.png, false",
        // a question mark is exactly one character
        "v?/a.txt,       v1/a.txt,           true",
        "v?/a.txt,       v12/a.txt,          false",
        "v?/a.txt,       v/a.txt,            false",
        // a double star spans segments, starting with none at all
        "'**/*.png',     logo.png,           true",
        "'**/*.png',     img/logo.png,       true",
        "'**/*.png',     a/b/c/logo.png,     true",
        "'**/*.png',     a/b/c/logo.gif,     false",
        // as the tail it may match nothing
        "internal/**,    internal/a.txt,     true",
        "internal/**,    internal/a/b/c.txt, true",
        "internal/**,    internal,           true",
        "internal/**,    public/a.txt,       false",
        // and in the middle it may span none, one or several
        "a/**/z.txt,     a/z.txt,            true",
        "a/**/z.txt,     a/b/z.txt,          true",
        "a/**/z.txt,     a/b/c/z.txt,        true",
        "a/**/z.txt,     a/b/c/y.txt,        false",
        "a/**/z.txt,     b/z.txt,            false",
        // several stars in one segment
        "*-*.log,        app-2026.log,       true",
        "*-*.log,        a-b-c.log,          true",
        "*-*.log,        app.log,            false",
        // and the mask which is everything
        "'**',           a/b/c.txt,          true"
    })
    public void matches(final String mask,
                        final String path,
                        final boolean expected) {
        Assertions.assertEquals(expected, new PathMask(mask).matches(path), mask + " vs " + path);
    }

    @Test
    public void testIncluding() {
        final FileFilter filter = PathMask.including("**/*.png", "**/*.css");
        Assertions.assertTrue(filter.accepts(ANY, "img/logo.png"));
        Assertions.assertTrue(filter.accepts(ANY, "style.css"));
        Assertions.assertFalse(filter.accepts(ANY, "secret.txt"));
    }

    @Test
    public void testExcluding() {
        final FileFilter filter = PathMask.excluding("internal/**", "*.key");
        Assertions.assertTrue(filter.accepts(ANY, "img/logo.png"));
        Assertions.assertFalse(filter.accepts(ANY, "internal/notes.txt"));
        Assertions.assertFalse(filter.accepts(ANY, "server.key"));
    }

    @Test
    public void testTwoFiltersAsOne() {
        final FileFilter filter = PathMask.including("**/*.png").and(PathMask.excluding("internal/**"));
        Assertions.assertTrue(filter.accepts(ANY, "img/logo.png"));
        Assertions.assertFalse(filter.accepts(ANY, "internal/logo.png"));
        Assertions.assertFalse(filter.accepts(ANY, "img/logo.txt"));
        Assertions.assertSame(filter, filter.and(null), "nothing to and with is nothing to decorate");
    }

    @Test
    public void testEmptyMaskIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PathMask(""));
        Assertions.assertThrows(IllegalArgumentException.class, PathMask::including);
    }
}
