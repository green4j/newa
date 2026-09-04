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

import io.github.green4j.newa.rest.TextErrorHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The same downloads as the ones the loop reads for itself, with the reading given to somebody else: what the
 * peer gets must not be able to tell which of the two answered it.
 * <p>
 * The reads are run by the test thread, in the order they were asked for, so nothing here waits on a pool.
 */
class ReadAheadFileServerTest {
    private static final int SIZE = 100_000;
    private static final int CHUNK = 8 * 1024;

    @TempDir
    private Path root;

    private final Deque<Runnable> reads = new ArrayDeque<>();

    private byte[] content;

    @BeforeEach
    public void setUp() throws IOException {
        content = new byte[SIZE];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(root.resolve("a.bin"), content);
    }

    private FileServerHandler handler() {
        return new FileServerHandler(
                FileSet.builder().serve("/files", root).build(),
                new TextErrorHandler(),
                null,
                null,
                CHUNK,
                reads::add
        );
    }

    private static final class Response {
        private HttpResponse head;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean open;
        private int regions;
    }

    private Response answer(final EmbeddedChannel channel,
                            final HttpRequest request) {
        final Response result = new Response();
        try {
            channel.writeInbound(request);

            while (!reads.isEmpty()) {
                reads.poll().run();
                channel.runPendingTasks(); // where the read comes back and the transfer goes on
            }

            if (channel.isActive()) {
                channel.flushOutbound();
            }

            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof FileRegion) {
                    result.regions++;
                }
                if (outbound instanceof HttpResponse) {
                    result.head = (HttpResponse) outbound;
                }
                if (outbound instanceof HttpContent) {
                    final ByteBuf chunk = ((HttpContent) outbound).content();
                    final byte[] read = new byte[chunk.readableBytes()];
                    chunk.getBytes(chunk.readerIndex(), read);
                    result.body.write(read, 0, read.length);
                }
                ReferenceCountUtil.release(outbound);
            }
            result.open = channel.isActive();
            return result;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static HttpRequest get(final String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    @Test
    public void testTheWholeFileIsAnswered() {
        final Response response = answer(new EmbeddedChannel(handler()), get("/files/a.bin"));

        Assertions.assertEquals(0, response.regions, "an embedded channel has nothing to send a region to");
        Assertions.assertEquals(200, response.head.status().code());
        Assertions.assertEquals(String.valueOf(SIZE), response.head.headers().get("Content-Length"));
        Assertions.assertArrayEquals(content, response.body.toByteArray());
        Assertions.assertTrue(response.open, "and the connection is as good as it was");
    }

    @Test
    public void testARangeIsAnsweredFromWhereItAsks() {
        final HttpRequest request = get("/files/a.bin");
        request.headers().set("Range", "bytes=1000-1999");

        final Response response = answer(new EmbeddedChannel(handler()), request);

        Assertions.assertEquals(206, response.head.status().code());
        Assertions.assertEquals("1000", response.head.headers().get("Content-Length"));
        final byte[] expected = new byte[1000];
        System.arraycopy(content, 1000, expected, 0, expected.length);
        Assertions.assertArrayEquals(expected, response.body.toByteArray());
    }

    @Test
    public void testAFileTruncatedWhileItIsBeingSentTakesTheConnection() throws IOException {
        final Path file = root.resolve("a.bin");
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelOutboundHandlerAdapter() {
                    private boolean done;

                    @Override
                    public void write(final ChannelHandlerContext ctx,
                                      final Object msg,
                                      final ChannelPromise promise) throws Exception {
                        if (!done && msg instanceof HttpResponse) {
                            done = true;
                            Files.write(file, new byte[0]);
                        }
                        super.write(ctx, msg, promise);
                    }
                },
                handler());

        final Response response = answer(channel, get("/files/a.bin"));

        Assertions.assertEquals(200, response.head.status().code());
        Assertions.assertEquals(String.valueOf(SIZE), response.head.headers().get("Content-Length"),
                "the length was promised before the file lost the bytes behind it");
        Assertions.assertTrue(response.body.size() < SIZE, "which it can no longer keep");
        Assertions.assertFalse(response.open,
                "so the connection goes, rather than leaving the peer reading a response which cannot end");
    }
}
