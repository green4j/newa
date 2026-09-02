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

import io.netty.buffer.ByteBuf;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A channel which cannot write a {@link FileRegion} at all - an embedded one has no socket under it - must
 * still be answered with the file, read into the process and written the ordinary way.
 */
class ZeroCopyFallbackTest {
    private static final int SIZE = 100_000;

    @TempDir
    private Path root;

    @Test
    public void testAChannelWhichCannotSendFileIsPumpedInstead() throws Exception {
        final byte[] content = new byte[SIZE];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(root.resolve("a.bin"), content);

        final FileSet files = FileSet.builder().serve("/files", root).build();
        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(files));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/files/a.bin"));
            channel.flushOutbound();

            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpResponse head = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof FileRegion) {
                    Assertions.fail("an embedded channel has nothing to send a file region to");
                }
                if (outbound instanceof HttpResponse) {
                    head = (HttpResponse) outbound;
                }
                if (outbound instanceof HttpContent) {
                    final ByteBuf chunk = ((HttpContent) outbound).content();
                    final byte[] read = new byte[chunk.readableBytes()];
                    chunk.getBytes(chunk.readerIndex(), read);
                    body.write(read);
                }
                ReferenceCountUtil.release(outbound);
            }

            Assertions.assertNotNull(head);
            Assertions.assertEquals(200, head.status().code());
            Assertions.assertEquals(String.valueOf(SIZE), head.headers().get("Content-Length"));
            Assertions.assertArrayEquals(content, body.toByteArray());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
