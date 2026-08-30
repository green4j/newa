package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
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
import io.netty.handler.codec.http.HttpResponseStatus;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cursor limit, and what the observer is told about it. A cursor is a database snapshot or a file handle
 * as often as not, so a request which cannot have one must be refused before it takes anything - not after.
 * <p>
 * Cursors are held open the only way they can be: by peers which stop reading. The socket buffers are tiny so
 * that happens at once.
 */
class ChunkedResponseLimitTest {
    private static final String HOST = "127.0.0.1";
    private static final String ITEM = "0123456789abcdef".repeat(16);
    private static final int TINY_SOCKET_BUFFER = 4 * 1024;
    private static final int MAX_OPEN_CURSORS = 2;

    private static final AtomicInteger OPENED = new AtomicInteger();

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
            return true;
        }

        @Override
        public void close() {
        }
    }

    /**
     * One shared record of what happened, written to by a fresh observer per request - which is what makes
     * "closed" attributable to the response it belongs to and not to whatever came after it.
     */
    private static final class RecordingObserver implements RestApiObserverFactory {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public RestApiObserver newObserver() {
            return new RestApiObserver() {
                @Override
                public void onCursorOpened(final int openCursors) {
                    events.add("opened " + openCursors);
                }

                @Override
                public void onCursorRefused(final int openCursors) {
                    events.add("refused " + openCursors);
                }

                @Override
                public void onCursorClosed(final int openCursors,
                                           final long bytes,
                                           final long durationNanos,
                                           final Outcome outcome) {
                    events.add("closed " + openCursors + " " + outcome);
                }

                @Override
                public void onResponseFailed(final HttpResponseStatus status,
                                             final Throwable error) {
                    events.add("failed " + status.code());
                }

                @Override
                public void onRequestCompleted(final HttpResponseStatus status,
                                               final long bytes,
                                               final long durationNanos) {
                    events.add("completed " + status.code());
                }
            };
        }

        private void awaitEvents(final int count) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
    }

    private final RecordingObserver observer = new RecordingObserver();
    private final List<Socket> stalled = new ArrayList<>();

    private ResponseChunks chunks;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "chunked-limit-test",
                "chunked limit tests",
                1,
                "test-build"
        );
        builder.get("/chunked/endless", new ChunkedJsonRestHandler(context -> new EndlessCursor()));
        builder.get("/chunked/fails-to-open", new ChunkedJsonRestHandler(context -> {
            throw new BadRequestException("not today");
        }));
        return builder.build();
    }

    private void initPipeline(final ChannelPipeline pipeline,
                              final RestApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        (channel, cause) -> { },
                        chunks,
                        observer
                )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
        OPENED.set(0);
        observer.events.clear();

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        httpClient = HttpClient.newHttpClient();

        // no stall timeout: these tests decide themselves when a cursor goes away
        chunks = ResponseChunks.builder()
                .maxOpenCursors(MAX_OPEN_CURSORS)
                .stallTimeoutMillis(0)
                .build();

        final RestApi api = buildTestApi();

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
     * Asks for the endless response and never reads a byte of it, so its cursor stays open.
     *
     * @return the open socket
     * @throws IOException if the request cannot be sent
     */
    private Socket holdACursor() throws Exception {
        final int before = chunks.openCursors();

        final Socket socket = new Socket();
        socket.setReceiveBufferSize(TINY_SOCKET_BUFFER);
        socket.connect(new InetSocketAddress(HOST, serverPort()));
        socket.getOutputStream().write(
                ("GET /v1/chunked/endless HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        stalled.add(socket);

        awaitOpenCursors(before + 1);
        return socket;
    }

    private void awaitOpenCursors(final int expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 15_000;
        while (chunks.openCursors() != expected) {
            Assertions.assertTrue(System.currentTimeMillis() < deadline,
                    "expected " + expected + " open cursors, got " + chunks.openCursors());
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> get(final String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://" + HOST + ':' + serverPort() + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    public void testOneCursorTooManyIsRefusedWith503AndNeverOpened() throws Exception {
        for (int i = 0; i < MAX_OPEN_CURSORS; i++) {
            holdACursor();
        }

        final int opened = OPENED.get();
        final HttpResponse<String> refused = get("/v1/chunked/endless");

        Assertions.assertEquals(503, refused.statusCode());
        // a deliberate answer, not a crash: no stack trace of the code which decided to send it
        Assertions.assertFalse(refused.body().contains("stacktrace"), refused.body());
        Assertions.assertTrue(refused.body().contains("Too many chunked responses"), refused.body());
        Assertions.assertEquals(opened, OPENED.get(),
                "the refused request opened a cursor anyway");
        Assertions.assertEquals(MAX_OPEN_CURSORS, chunks.openCursors());
    }

    @Test
    public void testARefusedRequestIsServedOnceASlotComesBack() throws Exception {
        final Socket first = holdACursor();
        for (int i = 1; i < MAX_OPEN_CURSORS; i++) {
            holdACursor();
        }
        Assertions.assertEquals(503, get("/v1/chunked/endless").statusCode());

        first.close();
        awaitOpenCursors(MAX_OPEN_CURSORS - 1);

        // the slot came back with the connection, so the next request is served
        holdACursor();
    }

    @Test
    public void testASlotIsGivenBackWhenTheCursorFailsToOpen() throws Exception {
        Assertions.assertEquals(400, get("/v1/chunked/fails-to-open").statusCode());

        Assertions.assertEquals(0, chunks.openCursors(),
                "a request which never opened a cursor kept its slot");
    }

    @Test
    public void testTheObserverIsToldWhatHappenedToEveryCursor() throws Exception {
        final Socket first = holdACursor();
        holdACursor();

        Assertions.assertEquals(503, get("/v1/chunked/endless").statusCode());
        Assertions.assertEquals(
                List.of("opened 1", "opened 2", "refused 2", "failed 503", "completed 503"),
                List.copyOf(observer.events),
                "a refused request is answered like any other, and completes like any other");

        observer.events.clear();
        first.close();
        awaitOpenCursors(MAX_OPEN_CURSORS - 1);
        observer.awaitEvents(2);

        Assertions.assertEquals(
                List.of("closed 1 ABANDONED", "completed 200"),
                List.copyOf(observer.events),
                "the cursor reports itself first, then the request completes");
    }
}
