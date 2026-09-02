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

import java.nio.file.Path;
import java.nio.file.Paths;

class FileSetTest {
    private static final Path WWW = Paths.get("/tmp/newa-test-www");
    private static final Path IMG = Paths.get("/tmp/newa-test-img");
    private static final Path LOGS = Paths.get("/tmp/newa-test-logs");
    private static final Path REPORT = Paths.get("/tmp/newa-test-report.pdf");

    private final FileSet.Match match = new FileSet.Match();

    private FileSet fileSet() {
        return FileSet.builder()
                .serve("/files", WWW)
                .serve("/files/img", IMG)
                .serve("/logs", LOGS)
                .file("/download/report.pdf", REPORT)
                .build();
    }

    private String tail(final String path) {
        return match.path().subSequence(match.tailStart(), match.tailEnd()).toString();
    }

    @Test
    public void testTheTailIsWhatIsLeftOfThePath() {
        Assertions.assertTrue(fileSet().match("/files/a/b.txt", match));
        Assertions.assertEquals(WWW.toAbsolutePath().normalize(), match.mapping().target());
        Assertions.assertEquals("/a/b.txt", tail("/files/a/b.txt"));
        Assertions.assertFalse(match.tailIsEmpty());
    }

    @Test
    public void testTheLongestServedPrefixWins() {
        Assertions.assertTrue(fileSet().match("/files/img/logo.png", match));
        Assertions.assertEquals(IMG.toAbsolutePath().normalize(), match.mapping().target());
        Assertions.assertEquals("/logo.png", tail("/files/img/logo.png"));
    }

    @Test
    public void testAPrefixWithNothingAfterItHasAnEmptyTail() {
        Assertions.assertTrue(fileSet().match("/files", match));
        Assertions.assertTrue(match.tailIsEmpty());

        Assertions.assertTrue(fileSet().match("/files/", match));
        Assertions.assertTrue(match.tailIsEmpty(), "a trailing slash is not something left of the path");
    }

    @Test
    public void testAnExactlyNamedFile() {
        final FileSet files = fileSet();
        Assertions.assertTrue(files.match("/download/report.pdf", match));
        Assertions.assertTrue(match.mapping().exact());
        Assertions.assertTrue(match.tailIsEmpty());

        Assertions.assertFalse(files.match("/download", match), "the file is served, the directory is not");
        Assertions.assertTrue(files.match("/download/report.pdf/more", match));
        Assertions.assertFalse(match.tailIsEmpty(), "which the handler refuses: a file has no tail");
    }

    @Test
    public void testTheQueryIsNotPartOfWhatIsAskedFor() {
        Assertions.assertTrue(fileSet().match("/files/a.txt?v=2", match));
        Assertions.assertEquals("/a.txt", tail("/files/a.txt?v=2"));

        Assertions.assertTrue(fileSet().match("/files/a.txt#top", match));
        Assertions.assertEquals("/a.txt", tail("/files/a.txt#top"));
    }

    @Test
    public void testAPathNothingOwnsIsNotThisHandlersToAnswer() {
        final FileSet files = fileSet();
        Assertions.assertFalse(files.match("/v1/hello/world", match));
        Assertions.assertFalse(files.match("/", match));
        Assertions.assertFalse(files.match("/filesystem/a.txt", match),
                "a prefix is whole segments, never the characters of one");
    }

    @Test
    public void testARootServingEverything() {
        final FileSet files = FileSet.builder().serve("/", WWW).build();
        Assertions.assertTrue(files.match("/a/b.txt", match));
        Assertions.assertEquals("/a/b.txt", tail("/a/b.txt"));
        Assertions.assertTrue(files.match("/", match));
        Assertions.assertTrue(match.tailIsEmpty());
    }

    @Test
    public void testFiltersDecorateOneAnother() {
        final FileSet files = FileSet.builder()
                .serve("/files", WWW,
                        PathMask.including("**/*.png").and(PathMask.excluding("internal/**")))
                .build();
        Assertions.assertTrue(files.match("/files/a.png", match));
        Assertions.assertTrue(match.mapping().accepts(WWW, "a.png"));
        Assertions.assertFalse(match.mapping().accepts(WWW, "a.txt"));
        Assertions.assertFalse(match.mapping().accepts(WWW, "internal/a.png"));
    }

    @Test
    public void testTheSamePathTwiceIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> FileSet.builder()
                .serve("/files", WWW)
                .serve("/files", IMG)
                .build());
    }

    @Test
    public void testAnEmptySetIsRefused() {
        Assertions.assertThrows(IllegalStateException.class, () -> FileSet.builder().build());
    }
}
