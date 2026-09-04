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

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a peer which stops reading costs. It must cost a suspended cursor and nothing else: no thread, no
 * limit on how many other such responses can be in flight, and no effect on any other connection. And it must
 * not cost that cursor forever - the watchdog gives up on it and the resources come back.
 */
class SlowConsumerChunkedTest {
    private static final String HOST = "127.0.0.1";
    private static final String ITEM = "0123456789abcdef".repeat(16);
    private static final int TINY_SOCKET_BUFFER = 4 * 1024;

    /** A second instead of the default thirty, so that giving up on a peer can be watched. */
    private static final int STALL_TIMEOUT_MS = 1000;
    private static final int STALLED_CONSUMERS = 20;

    private static final AtomicLong STEPS = new AtomicLong();
    private static final AtomicInteger OPENED = new AtomicInteger();
    private static final AtomicInteger CLOSED = new AtomicInteger();

    private ResponseChunks chunks;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;
    private final List<Socket> stalled = new ArrayList<>();

    /** An endless collection: it is the peer, never the source, that decides when this response ends. */
    private static final class EndlessCursor implements ChunkedJsonRestHandle.Cursor {
        private boolean started;

        private EndlessCursor() {
            OPENED.incrementAndGet();
        }

        @Override
        public boolean writeNext(final JsonGenerator output) {
            if (!started) {
                started = true;
                output.startArray();
            }
            for (int i = 0; i < 16; i++) {
                output.stringValue(ITEM);
            }
            STEPS.incrementAndGet();
            return true;
        }

        @Override
        public void close() {
            CLOSED.incrementAndGet();
        }
    }

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "slow-consumer-chunked-test",
                "slow consumer tests",
                1,
                "test-build"
        );
        builder.get("/chunked/endless", new ChunkedJsonRestHandler(context -> new EndlessCursor()));
        builder.getTxt("/small/txt", (context, output) -> output.append("small"));
        return builder.build();
    }

    private void initPipeline(final ChannelPipeline pipeline,
                              final RestApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        // a second instead of the default thirty, so that giving up on a peer can be watched. In a pipeline
        // built by RestServer this is withResponseDeadlineMs
        pipeline.addLast(new ResponseDeadlineHandler(STALL_TIMEOUT_MS));
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        // abandoned responses are the point of this test, so their channel errors are expected
                        (channel, cause) -> { },
                        chunks,
                        null
                )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
        STEPS.set(0);
        OPENED.set(0);
        CLOSED.set(0);

        // one event loop thread for every connection: if a stalled response held it, nothing else could be
        // served at all
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        httpClient = HttpClient.newHttpClient();

        final RestApi api = buildTestApi();

        chunks = ResponseChunks.defaults();

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(8 * 1024, 16 * 1024))
                .childOption(ChannelOption.SO_SNDBUF, TINY_SOCKET_BUFFER)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), api);
                    }
                });

        serverChannel = bootstrap.bind(HOST, 0).sync().channel();
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final Socket socket : stalled) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // going away anyway
            }
        }
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } finally {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
        }
    }

    private int serverPort() {
        final InetSocketAddress local = (InetSocketAddress) serverChannel.localAddress();
        return local.getPort();
    }

    /**
     * Asks for the endless response and never reads a byte of it.
     *
     * @return the open socket, closed by the teardown
     * @throws IOException if the request cannot be sent
     */
    private Socket startStalledConsumer() throws IOException {
        final Socket socket = new Socket();
        socket.setReceiveBufferSize(TINY_SOCKET_BUFFER);
        socket.connect(new InetSocketAddress(HOST, serverPort()));
        socket.getOutputStream().write(
                ("GET /v1/chunked/endless HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        stalled.add(socket);
        return socket;
    }

    private static void awaitAtLeast(final AtomicInteger counter,
                                     final int expected,
                                     final String what) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 15_000;
        while (counter.get() < expected) {
            Assertions.assertTrue(System.currentTimeMillis() < deadline,
                    what + ": expected at least " + expected + ", got " + counter.get());
            Thread.sleep(20);
        }
    }

    private String getSmall() throws Exception {
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(
                                URI.create("http://" + HOST + ':' + serverPort() + "/v1/small/txt"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test
    public void testStalledConsumerSuspendsItsCursorInsteadOfRunningAway() throws Exception {
        startStalledConsumer();
        awaitAtLeast(OPENED, 1, "cursor never opened");

        Thread.sleep(500);

        // the cursor is stepped only as far as the peer keeps up. With nothing holding it back, an endless
        // collection would have run to millions of steps by now, or to an OutOfMemoryError. How far it gets
        // instead is decided by the socket buffers, which the kernel is free to size as it likes - that it
        // stops dead once they are full is what the watchdog firing, in the test below, proves
        final long steps = STEPS.get();
        Assertions.assertTrue(steps < 1000,
                "the cursor was stepped " + steps + " times for a peer which is not reading");
    }

    @Test
    public void testStalledConsumersDoNotHoldUpAnyoneElse() throws Exception {
        // far more of them than any thread pool would have allowed: nothing here is per-response but the
        // cursor itself
        for (int i = 0; i < STALLED_CONSUMERS; i++) {
            startStalledConsumer();
        }
        awaitAtLeast(OPENED, STALLED_CONSUMERS, "not every cursor opened");

        Assertions.assertEquals("small", getSmall());
        Assertions.assertEquals("small", getSmall());
    }

    @Test
    public void testStalledConsumerHasItsCursorReleasedOnceItIsGivenUpOn() throws Exception {
        startStalledConsumer();
        awaitAtLeast(OPENED, 1, "cursor never opened");

        // nobody closed the connection and the peer is still there - it just is not reading. The watchdog is
        // what stops this from holding the cursor for as long as the connection lingers
        awaitAtLeast(CLOSED, 1, "the cursor was never released");

        Thread.sleep(300);
        Assertions.assertEquals(1, CLOSED.get(), "the cursor was released more than once");
    }

    @Test
    public void testEveryStalledConsumerHasItsCursorReleased() throws Exception {
        for (int i = 0; i < STALLED_CONSUMERS; i++) {
            startStalledConsumer();
        }
        awaitAtLeast(OPENED, STALLED_CONSUMERS, "not every cursor opened");
        awaitAtLeast(CLOSED, STALLED_CONSUMERS, "not every cursor was released");

        Thread.sleep(300);
        Assertions.assertEquals(STALLED_CONSUMERS, CLOSED.get(),
                "a cursor was released more than once");
    }

    @Test
    public void testCursorIsReleasedWhenThePeerDisconnectsMidResponse() throws Exception {
        final Socket consumer = startStalledConsumer();
        awaitAtLeast(OPENED, 1, "cursor never opened");

        consumer.close();

        awaitAtLeast(CLOSED, 1, "the cursor outlived the connection");
        Thread.sleep(300);
        Assertions.assertEquals(1, CLOSED.get(), "the cursor was released more than once");
    }
}
