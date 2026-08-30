package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunked responses over a real socket. A cursor is stepped only as far as the peer keeps up, so what these
 * pin is that the result is byte for byte the document the same content would have been rendered into in one
 * go, however many chunks it took to get there.
 */
class ChunkedResponseIntegrationTest {
    private static final String HOST = "127.0.0.1";
    private static final String LINE = "0123456789abcdef".repeat(16);
    private static final int LARGE_ITEM_COUNT = 20_000;

    /** Counts how many cursors were opened and closed, so a leaked one shows up. */
    private static final AtomicInteger OPENED = new AtomicInteger();
    private static final AtomicInteger CLOSED = new AtomicInteger();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;

    private static String itemOf(final int index) {
        return LINE + '-' + index;
    }

    /** Walks a range of items, a batch at a time, exactly as a database cursor would. */
    private static final class ItemCursor implements ChunkedJsonRestHandle.Cursor {
        private final int count;
        private final int batch;
        private int next;
        private boolean started;

        private ItemCursor(final int count, final int batch) {
            this.count = count;
            this.batch = batch;
            OPENED.incrementAndGet();
        }

        @Override
        public boolean writeNext(final JsonGenerator output) {
            if (!started) {
                started = true;
                output.startArray();
            }
            final int until = Math.min(count, next + batch);
            while (next < until) {
                output.stringValue(itemOf(next++));
            }
            // the array is left open on purpose: the framework ends the document
            return next < count;
        }

        @Override
        public void close() {
            CLOSED.incrementAndGet();
        }
    }

    private static String expectedJson(final int count) {
        final ByteArrayJsonGenerator expected =
                new ByteArrayJsonGenerator(new Utf8ByteArrayWriter(1024));
        final JsonGenerator generator = expected.start();
        generator.startArray();
        for (int i = 0; i < count; i++) {
            generator.stringValue(itemOf(i));
        }
        generator.endArray();
        return expected.finish().toString();
    }

    private static String expectedTxt(final int count) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(itemOf(i)).append(System.lineSeparator());
        }
        return result.toString();
    }

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "chunked-test",
                "chunked response tests",
                1,
                "test-build"
        );
        builder.get("/chunked/json", new ChunkedJsonRestHandler(context ->
                new ItemCursor(
                        context.queryParameters().valueRequiredAsInt("items"),
                        context.queryParameters().valueAsInt("batch", 100))));
        builder.get("/chunked/empty", new ChunkedJsonRestHandler(context -> new ItemCursor(0, 100)));
        // a cursor which is done before it writes anything at all: the last chunk carries no bytes
        builder.get("/chunked/nothing", new ChunkedJsonRestHandler(context -> new ChunkedJsonRestHandle.Cursor() {
            @Override
            public boolean writeNext(final JsonGenerator output) {
                return false;
            }

            @Override
            public void close() {
                CLOSED.incrementAndGet();
            }
        }));
        builder.get("/chunked/fails-early", new ChunkedJsonRestHandler(context -> {
            throw new BadRequestException("not today");
        }));
        builder.get("/chunked/txt", new ChunkedTxtRestHandler(context -> {
            final int count = context.queryParameters().valueRequiredAsInt("items");
            return new ChunkedTxtRestHandle.Cursor() {
                private int next;

                @Override
                public boolean writeNext(final io.github.green4j.newa.text.LineAppendable output) {
                    final int until = Math.min(count, next + 100);
                    while (next < until) {
                        output.appendln(itemOf(next++));
                    }
                    return next < count;
                }

                @Override
                public void close() {
                    CLOSED.incrementAndGet();
                }
            };
        }));
        // any content at all: the cursor writes bytes straight into the chunk
        builder.get("/chunked/bytes", new ChunkedRestHandler(
                HttpHeaderValues.APPLICATION_OCTET_STREAM,
                context -> {
                    final int count = context.queryParameters().valueRequiredAsInt("items");
                    return new ChunkedRestHandle.Cursor() {
                        private int next;

                        @Override
                        public boolean writeNext(final ByteBuf output) {
                            final int until = Math.min(count, next + 100);
                            while (next < until) {
                                output.writeCharSequence(itemOf(next++), StandardCharsets.UTF_8);
                            }
                            return next < count;
                        }

                        @Override
                        public void close() {
                            CLOSED.incrementAndGet();
                        }
                    };
                }));
        builder.getTxt("/small/txt", (context, output) -> output.append("small"));
        return builder.build();
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final RestApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        // no ChunkedWriteHandler here on purpose: it must be put in place by the first chunked response
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        (channel, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
        OPENED.set(0);
        CLOSED.set(0);

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        httpClient = HttpClient.newHttpClient();

        final RestApi api = buildTestApi();

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
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

    private HttpResponse<String> send(final String pathAndQuery) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(
                        URI.create("http://" + HOST + ':' + serverPort() + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private String get(final String pathAndQuery) throws Exception {
        final HttpResponse<String> response = send(pathAndQuery);
        Assertions.assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test
    public void testChunkedResponseIsChunked() throws Exception {
        final HttpResponse<String> response = send("/v1/chunked/json?items=1000");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("chunked",
                response.headers().firstValue("transfer-encoding").orElse(null));
        Assertions.assertTrue(response.headers().firstValue("content-length").isEmpty());
    }

    @Test
    public void testLargeJsonCollectionArrivesAsTheDocumentItWouldHaveBeenRenderedInto() throws Exception {
        Assertions.assertEquals(expectedJson(LARGE_ITEM_COUNT),
                get("/v1/chunked/json?items=" + LARGE_ITEM_COUNT));
    }

    @Test
    public void testDocumentIsTheSameWhateverTheChunkBoundariesFallOn() throws Exception {
        final String expected = expectedJson(3000);

        // the batch decides where a chunk ends, so this walks the boundary across the document
        for (final int batch : new int[]{1, 2, 7, 13, 100, 999, 4000}) {
            Assertions.assertEquals(expected,
                    get("/v1/chunked/json?items=3000&batch=" + batch),
                    "damaged with batch " + batch);
        }
    }

    @Test
    public void testLargeTxtCollectionArrivesIntact() throws Exception {
        Assertions.assertEquals(expectedTxt(LARGE_ITEM_COUNT),
                get("/v1/chunked/txt?items=" + LARGE_ITEM_COUNT));
    }

    @Test
    public void testEmptyCollectionArrivesAsAnEmptyArray() throws Exception {
        Assertions.assertEquals(expectedJson(0), get("/v1/chunked/empty"));
    }

    @Test
    public void testCursorWhichWritesNothingArrivesAsAnEmptyBody() throws Exception {
        // returning null instead of an empty chunk here would leave ChunkedWriteHandler waiting for a chunk
        // which is never coming
        Assertions.assertEquals("", get("/v1/chunked/nothing"));
    }

    @Test
    public void testChunkedResponsesKeepTheConnectionAlive() throws Exception {
        // a mis-framed chunked body would show up as the next response failing or arriving damaged
        final String expected = expectedJson(500);
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(expected, get("/v1/chunked/json?items=500"),
                    "response " + i + " is damaged");
        }
        Assertions.assertEquals("small", get("/v1/small/txt"));
    }

    @Test
    public void testCursorIsClosedOnceTheResponseIsDone() throws Exception {
        get("/v1/chunked/json?items=" + LARGE_ITEM_COUNT);

        Assertions.assertEquals(1, OPENED.get());
        Assertions.assertEquals(1, CLOSED.get());
    }

    @Test
    public void testAnyContentTypeCanBeStreamedFromABytesCursor() throws Exception {
        final StringBuilder expected = new StringBuilder();
        for (int i = 0; i < LARGE_ITEM_COUNT; i++) {
            expected.append(itemOf(i));
        }

        final HttpResponse<String> response = send("/v1/chunked/bytes?items=" + LARGE_ITEM_COUNT);

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("chunked",
                response.headers().firstValue("transfer-encoding").orElse(null));
        Assertions.assertEquals(expected.toString(), response.body());
        Assertions.assertEquals(1, CLOSED.get());
    }

    @Test
    public void testFailureWhileOpeningTheCursorStillGetsAnErrorResponse() throws Exception {
        // nothing has been sent yet, so the chunked response can still be replaced by an ordinary error
        final HttpResponse<String> response = send("/v1/chunked/fails-early");

        Assertions.assertEquals(400, response.statusCode());
        Assertions.assertTrue(response.body().contains("not today"), response.body());
        Assertions.assertEquals(0, OPENED.get());
    }
}
