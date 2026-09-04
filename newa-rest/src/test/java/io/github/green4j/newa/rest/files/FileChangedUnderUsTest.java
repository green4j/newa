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

import io.github.green4j.newa.rest.HttpException;
import io.github.green4j.newa.rest.HttpObserver;
import io.github.green4j.newa.rest.HttpObserverFactory;
import io.github.green4j.newa.rest.TextErrorHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A file is not a snapshot: it can be appended to, truncated, replaced or unlinked while it is being sent,
 * and a server which measured it a moment ago has already promised a length it may no longer be able to keep.
 * <p>
 * The handler answers from an open descriptor rather than from a path, so what it sends is the file it
 * measured, whatever happens to the name afterwards. What it cannot do is send bytes which stopped existing -
 * and there the only thing left is to not leave the peer reading a response which will never end.
 */
class FileChangedUnderUsTest {
    private static final int SIZE = 64 * 1024;

    @TempDir
    private Path root;

    private Path file;

    @BeforeEach
    public void setUp() throws IOException {
        file = root.resolve("a.bin");
        Files.write(file, new byte[SIZE]);
    }

    private FileSet files() {
        return FileSet.builder().serve("/files", root).build();
    }

    /**
     * The stages of the one request each of these tests makes, in the order they were reported. What a
     * truncated response looks like to an observer is the whole of the question here: the head has gone,
     * so the status stands, and the only thing which can still say the response was not the one promised is
     * {@code onResponseFailed}.
     */
    private static final class Recorder implements HttpObserver, HttpObserverFactory {
        private final List<String> stages = new ArrayList<>();
        private HttpResponseStatus failedWith;
        private Throwable failure;
        private long completedBytes = -1;

        @Override
        public HttpObserver newObserver() {
            return this; // one request per channel here, so one of these is enough
        }

        @Override
        public void onRequestNotRouted(final HttpException cause) {
            stages.add("notRouted");
        }

        @Override
        public void onResponseFailed(final HttpResponseStatus status,
                                     final Throwable error) {
            stages.add("failed");
            failedWith = status;
            failure = error;
        }

        @Override
        public void onRequestCompleted(final HttpResponseStatus status,
                                       final long bytes,
                                       final long durationNanos) {
            stages.add("completed");
            completedBytes = bytes;
        }
    }

    private static final class Response {
        private HttpResponse head;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean open;
    }

    private Response request(final EmbeddedChannel channel) {
        final Response result = new Response();
        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/files/a.bin"));
            if (channel.isActive()) {
                channel.flushOutbound(); // it may be closed already, which is itself one of the answers
            }

            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof HttpResponse) {
                    result.head = (HttpResponse) outbound;
                }
                if (outbound instanceof HttpContent) {
                    final ByteBuf content = ((HttpContent) outbound).content();
                    final byte[] read = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), read);
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

    /**
     * Does something to the file the moment the head of the response goes past, which is after the handler
     * has opened and measured it and before a single byte of it has been read.
     */
    private static final class WhenTheHeadIsWritten extends ChannelOutboundHandlerAdapter {
        private final Runnable action;
        private boolean done;

        private WhenTheHeadIsWritten(final Runnable action) {
            this.action = action;
        }

        @Override
        public void write(final ChannelHandlerContext ctx,
                          final Object msg,
                          final ChannelPromise promise) throws Exception {
            if (!done && msg instanceof HttpResponse) {
                done = true;
                action.run();
            }
            super.write(ctx, msg, promise);
        }
    }

    @Test
    public void testAFileWhichIsGoneBeforeItIsResolved() {
        final Recorder observer = new Recorder();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx,
                                            final Object msg) {
                        try {
                            Files.delete(file);
                        } catch (final IOException e) {
                            throw new AssertionError(e);
                        }
                        ctx.fireChannelRead(msg);
                    }
                },
                new FileServerHandler(files(), new TextErrorHandler(), null, observer));

        final Response response = request(channel);
        Assertions.assertNotNull(response.head);
        Assertions.assertEquals(404, response.head.status().code(),
                "nothing was written before it was opened, so it can still be answered honestly");
        Assertions.assertEquals(Arrays.asList("notRouted", "completed"), observer.stages,
                "an error which was answered is told once, by the stage which answered it");
    }

    @Test
    public void testAFileUnlinkedWhileItIsBeingSent() throws IOException {
        final Recorder observer = new Recorder();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new WhenTheHeadIsWritten(() -> {
                    try {
                        Files.delete(file);
                    } catch (final IOException whereDeletingAnOpenFileIsRefused) {
                        // then there is nothing to test here, and nothing to worry about either
                    }
                }),
                new FileServerHandler(files(), new TextErrorHandler(), null, observer));

        final Response response = request(channel);
        Assertions.assertEquals(200, response.head.status().code());
        Assertions.assertEquals(String.valueOf(SIZE), response.head.headers().get("Content-Length"));
        Assertions.assertEquals(SIZE, response.body.size(),
                "the descriptor was open before the name went, and the bytes are behind the descriptor");
        Assertions.assertTrue(response.open, "so the connection is as good as it was");
        Assertions.assertEquals(Arrays.asList("completed"), observer.stages,
                "a response the peer got whole is not a failure of anything");
        Assertions.assertEquals(SIZE, observer.completedBytes);
    }

    @Test
    public void testAFileReplacedWhileItIsBeingSent() throws IOException {
        final Path other = root.resolve("other.bin");
        Files.write(other, new byte[SIZE / 2]);

        final Recorder observer = new Recorder();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new WhenTheHeadIsWritten(() -> {
                    try {
                        Files.move(other, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (final IOException e) {
                        throw new AssertionError(e);
                    }
                }),
                new FileServerHandler(files(), new TextErrorHandler(), null, observer));

        final Response response = request(channel);
        Assertions.assertEquals(200, response.head.status().code());
        Assertions.assertEquals(String.valueOf(SIZE), response.head.headers().get("Content-Length"));
        Assertions.assertEquals(SIZE, response.body.size(),
                "a name pointed at something else is not the file this response is of");
        Assertions.assertTrue(response.open);
        Assertions.assertEquals(Arrays.asList("completed"), observer.stages);
    }

    @Test
    public void testAFileTruncatedWhileItIsBeingSent() {
        final Recorder observer = new Recorder();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new WhenTheHeadIsWritten(() -> {
                    try {
                        Files.write(file, new byte[0]);
                    } catch (final IOException e) {
                        throw new AssertionError(e);
                    }
                }),
                new FileServerHandler(files(), new TextErrorHandler(), null, observer));

        final Response response = request(channel);
        Assertions.assertEquals(200, response.head.status().code());
        Assertions.assertEquals(String.valueOf(SIZE), response.head.headers().get("Content-Length"),
                "the length was promised before the file lost the bytes behind it");
        Assertions.assertTrue(response.body.size() < SIZE, "which it can no longer keep");
        Assertions.assertFalse(response.open,
                "so the connection goes, rather than leaving the peer reading a response which cannot end");
        Assertions.assertEquals(Arrays.asList("failed", "completed"), observer.stages,
                "and the observer is told the response was not the one promised, before the request closes");
        Assertions.assertEquals(HttpResponseStatus.OK, observer.failedWith,
                "with the status the head already carried: there is nothing left to answer with");
        Assertions.assertNotNull(observer.failure,
                "and the cause is reported in full, which is the only place it is");
        Assertions.assertEquals(response.body.size(), observer.completedBytes,
                "and the bytes are what really reached the channel, not what was promised");
    }
}
