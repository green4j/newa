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

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.rest.HttpErrorHandler;
import io.github.green4j.newa.rest.FullHttpResponseContent;
import io.github.green4j.newa.rest.HttpException;
import io.github.green4j.newa.rest.PathNotFoundException;
import io.github.green4j.newa.rest.TextErrorHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FileRegion;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What happens to a channel of this handler which fails. The handler used to leave that to whoever stood
 * behind it: with a {@code RestApiHandler} there the connection was closed, and in a pipeline which serves
 * only files - where this handler is the last one - the event reached the tail of the pipeline and the
 * connection stayed open, holding a half written response and the file open inside it.
 * <p>
 * So: the cause is reported once, the connection goes, and whatever the response still held goes with it.
 */
class ChannelErrorTest {
    private static final String HOST = "127.0.0.1";

    /**
     * Larger than anything the two ends can buffer, so a client which never reads leaves the file queued
     * rather than sent.
     */
    private static final int BIG_FILE_SIZE = 8 * 1024 * 1024;

    private static final long TIMEOUT_MILLIS = 10_000;

    @TempDir
    private Path root;

    @BeforeEach
    public void setUp() throws IOException {
        Files.createDirectories(root.resolve("img"));
        Files.write(root.resolve("img/big.bin"), new byte[BIG_FILE_SIZE]);
        Files.write(root.resolve("small.txt"), "small".getBytes(StandardCharsets.UTF_8));
    }

    private FileSet files() {
        return FileSet.builder().serve("/files", root).build();
    }

    @Test
    public void testAFailedChannelIsClosedWithNothingBehindTheFiles() {
        final Recorder errors = new Recorder();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new FileServerHandler(files(), new TextErrorHandler(), errors, null));
        try {
            final IOException boom = new IOException("boom");
            channel.pipeline().fireExceptionCaught(boom);

            Assertions.assertEquals(1, errors.causes.size(), "the cause is reported exactly once");
            Assertions.assertSame(boom, errors.causes.get(0));
            Assertions.assertFalse(channel.isOpen(),
                    "and the connection goes: nobody else was going to close it");
            channel.checkException(); // and it did not travel on to the tail of the pipeline
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testAFailureOfTheHandlerItselfEndsTheConnection() {
        final Recorder errors = new Recorder();
        final Sentinel behind = new Sentinel();
        final RuntimeException boom = new RuntimeException("cannot render that");

        final EmbeddedChannel channel = new EmbeddedChannel(
                new FileServerHandler(files(), new FailingHttpErrorHandler(boom), errors, null),
                behind);
        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/files/missing.txt");
        try {
            // the 404 this asks for cannot be rendered, so the failure is the handler's own
            channel.writeInbound(request);

            Assertions.assertEquals(1, errors.causes.size());
            Assertions.assertSame(boom, errors.causes.get(0));
            Assertions.assertEquals(0, request.refCnt(), "the request is released whatever went wrong");
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(0, behind.caught.get(),
                    "and the handler behind is not told about it a second time");
            channel.checkException();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testAFileStillOnItsWayOutIsReleasedWithTheChannel() throws Exception {
        final Recorder errors = new Recorder();
        final AtomicReference<Channel> accepted = new AtomicReference<>();
        final AtomicReference<FileRegion> written = new AtomicReference<>();

        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) {
                            accepted.set(ch);

                            final ChannelPipeline pipeline = ch.pipeline();
                            // right at the socket: what is caught here is what the transport was asked to
                            // write, and what has to be released if it is never written
                            pipeline.addLast(new ChannelOutboundHandlerAdapter() {
                                @Override
                                public void write(final ChannelHandlerContext ctx,
                                                  final Object msg,
                                                  final ChannelPromise promise) throws Exception {
                                    if (msg instanceof FileRegion) {
                                        written.compareAndSet(null, (FileRegion) msg);
                                    }
                                    super.write(ctx, msg, promise);
                                }
                            });
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536, true));
                            // nothing behind the files: the pipeline where the failure used to be nobody's
                            pipeline.addLast(new FileServerHandler(
                                    files(), new TextErrorHandler(), errors, null));
                        }
                    });

            final Channel server = bootstrap.bind(HOST, 0).sync().channel();
            final int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (Socket client = new Socket()) {
                client.setReceiveBufferSize(8 * 1024);
                client.connect(new InetSocketAddress(HOST, port), (int) TIMEOUT_MILLIS);
                client.getOutputStream().write(
                        ("GET /files/img/big.bin HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                final FileRegion region = await(written, "the file was never written");
                // nothing is read on this side, so what the socket would not take is sitting in the queue,
                // and the open file with it
                Assertions.assertTrue(region.transferred() < region.count(),
                        "the file went out whole, so there is nothing queued to lose");
                Assertions.assertEquals(1, region.refCnt());

                final Channel channel = accepted.get();
                channel.pipeline().fireExceptionCaught(new IOException("boom"));

                Assertions.assertTrue(channel.closeFuture().await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "a failed channel is closed by the file handler itself");
                awaitReleased(region);
                Assertions.assertEquals(1, errors.causes.size());
            }
            server.close().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static <T> T await(final AtomicReference<T> reference,
                               final String message) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        do {
            final T value = reference.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError(message);
    }

    /**
     * The queue is failed on the event loop, a moment after the close itself is done with.
     *
     * @param region which the connection took with it
     */
    private static void awaitReleased(final FileRegion region) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (region.refCnt() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(0, region.refCnt(),
                "closing the connection is what releases the file it was still writing");
    }

    private static final class Recorder implements ChannelErrorHandler {
        private final List<Throwable> causes = new ArrayList<>();

        @Override
        public void onError(final Channel channel,
                            final Throwable cause) {
            causes.add(cause);
        }
    }

    /**
     * Stands behind the file handler and counts what reaches it.
     */
    private static final class Sentinel extends ChannelInboundHandlerAdapter {
        private final AtomicInteger caught = new AtomicInteger();

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
            caught.incrementAndGet();
        }
    }

    /**
     * Fails where nothing is expected to: while rendering the response a refused request is answered with.
     */
    private static final class FailingHttpErrorHandler implements HttpErrorHandler {
        private final RuntimeException cause;
        private final HttpErrorHandler delegate = new TextErrorHandler();

        private FailingHttpErrorHandler(final RuntimeException cause) {
            this.cause = cause;
        }

        @Override
        public FullHttpResponseContent handle(final HttpException error) {
            if (error instanceof PathNotFoundException) {
                throw cause;
            }
            return delegate.handle(error);
        }
    }
}
