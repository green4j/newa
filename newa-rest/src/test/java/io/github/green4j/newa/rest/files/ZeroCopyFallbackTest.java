/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
