package io.github.green4j.newa.rest.files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

class PathMaskTest {
    private static final Path ANY = Paths.get("/tmp/newa-test-any");

    @Test
    public void testLiteral() {
        final PathMask mask = new PathMask("img/logo.png");
        Assertions.assertTrue(mask.matches("img/logo.png"));
        Assertions.assertFalse(mask.matches("img/logo.jpg"));
        Assertions.assertFalse(mask.matches("logo.png"));
        Assertions.assertFalse(mask.matches("a/img/logo.png"));
    }

    @Test
    public void testStarStaysWithinOneSegment() {
        final PathMask mask = new PathMask("img/*.png");
        Assertions.assertTrue(mask.matches("img/logo.png"));
        Assertions.assertTrue(mask.matches("img/.png"));
        Assertions.assertFalse(mask.matches("img/icons/logo.png"));
    }

    @Test
    public void testQuestionMarkIsOneCharacter() {
        final PathMask mask = new PathMask("v?/a.txt");
        Assertions.assertTrue(mask.matches("v1/a.txt"));
        Assertions.assertFalse(mask.matches("v12/a.txt"));
        Assertions.assertFalse(mask.matches("v/a.txt"));
    }

    @Test
    public void testDoubleStarSpansSegments() {
        final PathMask mask = new PathMask("**/*.png");
        Assertions.assertTrue(mask.matches("logo.png"), "no segments at all is what it starts with");
        Assertions.assertTrue(mask.matches("img/logo.png"));
        Assertions.assertTrue(mask.matches("a/b/c/logo.png"));
        Assertions.assertFalse(mask.matches("a/b/c/logo.gif"));
    }

    @Test
    public void testDoubleStarAsTheTail() {
        final PathMask mask = new PathMask("internal/**");
        Assertions.assertTrue(mask.matches("internal/a.txt"));
        Assertions.assertTrue(mask.matches("internal/a/b/c.txt"));
        Assertions.assertTrue(mask.matches("internal"), "a trailing one may match nothing");
        Assertions.assertFalse(mask.matches("public/a.txt"));
    }

    @Test
    public void testDoubleStarInTheMiddle() {
        final PathMask mask = new PathMask("a/**/z.txt");
        Assertions.assertTrue(mask.matches("a/z.txt"));
        Assertions.assertTrue(mask.matches("a/b/z.txt"));
        Assertions.assertTrue(mask.matches("a/b/c/z.txt"));
        Assertions.assertFalse(mask.matches("a/b/c/y.txt"));
        Assertions.assertFalse(mask.matches("b/z.txt"));
    }

    @Test
    public void testSeveralStarsInOneSegment() {
        final PathMask mask = new PathMask("*-*.log");
        Assertions.assertTrue(mask.matches("app-2026.log"));
        Assertions.assertTrue(mask.matches("a-b-c.log"));
        Assertions.assertFalse(mask.matches("app.log"));
    }

    @Test
    public void testEverything() {
        Assertions.assertTrue(new PathMask("**").matches("a/b/c.txt"));
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
