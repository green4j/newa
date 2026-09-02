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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every way of asking for a file which is not this handler\'s to answer with. What is asserted throughout is
 * the same 404: a file kept out by a rule, a file outside the root and a file which is not there at all must
 * be indistinguishable, or asking becomes a way of finding out what is there.
 * <p>
 * Two of these were live holes before they were tests. A percent-encoded separator made the router match one
 * mapping and the file system resolve into another; and a filter matched against the name as asked, rather
 * than the name the file system answers to, let {@code /INTERNAL/} past a rule about {@code internal/} on
 * every file system which does not tell the two apart.
 */
class AccessControlTest {
    @TempDir
    private Path root;

    @BeforeEach
    public void setUp() throws IOException {
        Files.createDirectories(root.resolve("img"));
        Files.createDirectories(root.resolve("internal"));
        Files.write(root.resolve("img/logo.png"), "png".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("internal/secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("public.txt"), "public".getBytes(StandardCharsets.UTF_8));
    }

    private int statusOf(final FileSet files,
                         final String uri) {
        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(files));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
            channel.flushOutbound();
            int status = -1;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof HttpResponse) {
                    status = ((HttpResponse) outbound).status().code();
                }
                ReferenceCountUtil.release(outbound);
            }
            return status;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private FileSet filtered() {
        return FileSet.builder()
                .serve("/files", root, PathMask.excluding("internal/**"))
                .build();
    }

    @Test
    public void testAFilterIsAboutTheNameTheFileSystemAnswersTo() {
        final FileSet files = filtered();
        Assertions.assertEquals(200, statusOf(files, "/files/public.txt"));
        Assertions.assertEquals(404, statusOf(files, "/files/internal/secret.txt"));
        Assertions.assertEquals(404, statusOf(files, "/files/INTERNAL/secret.txt"),
                "on a file system which does not tell the case apart this is the same file");
        Assertions.assertEquals(404, statusOf(files, "/files/Internal/Secret.txt"));
    }

    @Test
    public void testAnEncodedSeparatorIsRefused() {
        final FileSet files = FileSet.builder()
                .serve("/files", root)
                .serve("/files/img", root.resolve("img"), PathMask.excluding("**"))
                .build();

        Assertions.assertEquals(404, statusOf(files, "/files/img/logo.png"),
                "the mapping of the tree it is in refuses everything");
        Assertions.assertEquals(404, statusOf(files, "/files/img%2flogo.png"),
                "and an encoded slash must not turn it into a request the outer mapping answers");
        Assertions.assertEquals(404, statusOf(files, "/files/img%2Flogo.png"));
        Assertions.assertEquals(404, statusOf(files, "/files/img%5clogo.png"));
    }

    @Test
    public void testAFileAMoreSpecificMappingOwnsIsAskedForByItsOwnPath() throws IOException {
        Files.createSymbolicLink(root.resolve("pictures"), root.resolve("img"));

        final FileSet files = FileSet.builder()
                .serve("/files", root)
                .serve("/files/img", root.resolve("img"), PathMask.excluding("**"))
                .build();

        Assertions.assertEquals(404, statusOf(files, "/files/pictures/logo.png"),
                "reached through a link or not, the rules of the mapping it lives in are the ones that hold");
    }

    @Test
    public void testWhatIsOutsideTheRoot() throws IOException {
        final Path outside = Files.createTempFile("newa-outside", ".txt");
        try {
            Files.write(outside, "outside".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(root.resolve("escape.txt"), outside);

            final FileSet files = filtered();
            Assertions.assertEquals(404, statusOf(files, "/files/../" + outside.getFileName()));
            Assertions.assertEquals(404, statusOf(files, "/files/%2e%2e/" + outside.getFileName()));
            Assertions.assertEquals(404, statusOf(files, "/files/img/../../etc/passwd"));
            Assertions.assertEquals(404, statusOf(files, "/files/escape.txt"),
                    "a link is followed before the root is compared, not after");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void testWhatIsNotAFile() throws IOException {
        final FileSet files = filtered();
        Assertions.assertEquals(404, statusOf(files, "/files/img"), "a directory with no index in it");
        Assertions.assertEquals(404, statusOf(files, "/files"), "nor is the root one");
        Assertions.assertEquals(404, statusOf(files, "/files/missing.txt"));
        Assertions.assertEquals(404, statusOf(files, "/files/logo.png"),
                "a name which is only a name under another directory");
    }

    @Test
    public void testAPathTooLongToBeAName() {
        final StringBuilder uri = new StringBuilder("/files/");
        for (int i = 0; i < 8192; i++) {
            uri.append('a');
        }
        Assertions.assertEquals(404, statusOf(filtered(), uri.toString()));

        final StringBuilder deep = new StringBuilder("/files");
        for (int i = 0; i < 2000; i++) {
            deep.append("/a");
        }
        Assertions.assertEquals(404, statusOf(filtered(), deep.toString()));
    }

    @Test
    public void testWhatIsNotAPathAtAll() {
        final FileSet files = filtered();
        Assertions.assertEquals(404, statusOf(files, "/files/%00"), "a NUL ends a name for the OS");
        Assertions.assertEquals(404, statusOf(files, "/files/public.txt%00.png"));
        Assertions.assertEquals(404, statusOf(files, "/files/%zz"));
        Assertions.assertEquals(404, statusOf(files, "/files/%2"));
        Assertions.assertEquals(404, statusOf(files, "/files/%c0%ae%c0%ae/public.txt"),
                "an overlong encoding of '..' is not '..' to a UTF-8 decoder, and is not a file either");
        Assertions.assertEquals(200, statusOf(files, "/files/public.txt?a=../../etc/passwd"),
                "the query is not part of what is being asked for");
    }
}
