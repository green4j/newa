package io.github.green4j.newa.rest;

import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Large responses over a real socket. The point of the size used here is not the size itself but that it is
 * comfortably past {@link ResponseBuffers#baseSize()}: the response must arrive intact, and the buffer it was
 * rendered in must be kept at that size while such responses keep coming.
 * <p>
 * The server runs a single event loop thread so that the probe endpoints, which report the sizes of the
 * thread-local buffers, observe the very same thread that rendered the large response.
 */
class LargeResponseIntegrationTest {
    private static final String HOST = "127.0.0.1";
    private static final int LARGE_CONTENT_SIZE = 2 * 1024 * 1024;

    /** Reaches the same thread-local generator the JSON handlers use. */
    private static final class JsonGeneratorProbe extends AbstractApplicationJsonHandler {
        ByteArrayJsonGenerator current() {
            return jsonGenerator();
        }
    }

    /** Reaches the same thread-local line builder the plain-text handlers use. */
    private static final class LineBuilderProbe extends AbstractTextPlainHandler {
        ByteArrayLineBuilder current() {
            return lineBuilder();
        }
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;

    private static String contentOf(final int size) {
        final StringBuilder result = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            result.append((char) ('a' + i % 26));
        }
        return result.toString();
    }

    private static RestApi buildTestApi() {
        final JsonGeneratorProbe jsonProbe = new JsonGeneratorProbe();
        final LineBuilderProbe txtProbe = new LineBuilderProbe();

        final RestApiBuilder builder = new RestApiBuilder(
                "large-response-test",
                "large response tests",
                1,
                "test-build"
        );
        builder.getJson("/large/json", (context, output) ->
                output.stringValue(
                        contentOf(context.queryParameters().valueRequiredAsInt("size"))));
        builder.getTxt("/large/txt", (context, output) ->
                output.append(
                        contentOf(context.queryParameters().valueRequiredAsInt("size"))));
        builder.getJson("/small/json", (context, output) -> output.stringValue("small"));
        builder.getTxt("/small/txt", (context, output) -> output.append("small"));
        // each probe is rendered by the other content type's buffer, so reading a size never disturbs it
        builder.getTxt("/probe/json-buffer", (context, output) ->
                output.append(Integer.toString(jsonProbe.current().capacity())));
        builder.getJson("/probe/txt-buffer", (context, output) ->
                output.numberValue(txtProbe.current().capacity()));
        return builder.build();
    }

    private static void initPipeline(final ChannelPipeline pipeline,
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
                        (channel, cause) -> {
                            throw new AssertionError(cause);
                        }
                )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
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

    private String get(final String pathAndQuery) throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort() + pathAndQuery
        );
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        return response.body();
    }

    private int retainedJsonBufferSize() throws Exception {
        return Integer.parseInt(get("/v1/probe/json-buffer"));
    }

    private int retainedTxtBufferSize() throws Exception {
        return Integer.parseInt(get("/v1/probe/txt-buffer"));
    }

    @Test
    public void testLargeJsonResponseArrivesIntact() throws Exception {
        final String body = get("/v1/large/json?size=" + LARGE_CONTENT_SIZE);

        Assertions.assertEquals(LARGE_CONTENT_SIZE + 2, body.length());
        Assertions.assertEquals('"', body.charAt(0));
        Assertions.assertEquals('"', body.charAt(body.length() - 1));
        Assertions.assertEquals(contentOf(LARGE_CONTENT_SIZE),
                body.substring(1, body.length() - 1));
    }

    @Test
    public void testLargeTxtResponseArrivesIntact() throws Exception {
        final String body = get("/v1/large/txt?size=" + LARGE_CONTENT_SIZE);

        Assertions.assertEquals(contentOf(LARGE_CONTENT_SIZE), body);
    }

    @Test
    public void testRepeatedLargeResponsesStayIntactAndKeepTheirBuffer() throws Exception {
        final String expected = contentOf(LARGE_CONTENT_SIZE);

        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals(expected,
                    get("/v1/large/txt?size=" + LARGE_CONTENT_SIZE),
                    "response " + i + " is damaged");
        }

        // still serving them at that size, so the buffer must be held rather than re-grown per request
        Assertions.assertTrue(retainedTxtBufferSize() > ResponseBuffers.baseSize());
    }

    @Test
    public void testLargeJsonResponseGrowsItsBufferAndKeepsIt() throws Exception {
        get("/v1/large/json?size=" + LARGE_CONTENT_SIZE);

        final int grown = retainedJsonBufferSize();
        Assertions.assertTrue(grown > ResponseBuffers.baseSize());

        // small responses do not undo it by themselves: only the observation window passing does, which
        // RetainedBufferTest covers on a clock it can move
        for (int i = 0; i < 50; i++) {
            get("/v1/small/json");
        }

        Assertions.assertEquals(grown, retainedJsonBufferSize());
    }

    @Test
    public void testOrdinaryResponsesNeverGrowTheBufferAtAll() throws Exception {
        for (int i = 0; i < 50; i++) {
            get("/v1/small/txt");
        }

        Assertions.assertEquals(ResponseBuffers.baseSize(), retainedTxtBufferSize());
    }
}
