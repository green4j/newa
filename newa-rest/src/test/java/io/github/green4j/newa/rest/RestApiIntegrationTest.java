package io.github.green4j.newa.rest;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

class RestApiIntegrationTest {
    private static final String HOST = "127.0.0.1";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "integration-test",
                "integration tests",
                1,
                "test-build"
        );
        builder.getJson(
                "/hello/{name}",
                (context,
                 output) ->
                        output.stringValue(
                                "Hello " + context.pathParameters().valueRequired("name") + '!'
                        )
        ).withPathParameterDescriptions("name - Guest display name");
        builder.getTxt(
                "/ping",
                (context,
                 output) ->
                        output.append("pong")
        );
        builder.get("/echo-query", (context, result) -> {
            final NamedMultiValues qp = context.queryParameters();
            final String greeting = qp.valueRequired("greeting");
            final String name = qp.valueRequired("name");
            final byte[] body = (greeting + " " + name)
                    .getBytes(StandardCharsets.UTF_8);
            result.ok(HttpHeaderValues.TEXT_PLAIN,
                    body, 0, body.length);
        });
        builder.post("/echo-form", (context, result) -> {
            final NamedMultiValues fp = context.formParameters();
            final String color = fp.valueRequired("color");
            final byte[] body = color
                    .getBytes(StandardCharsets.UTF_8);
            result.ok(HttpHeaderValues.TEXT_PLAIN,
                    body, 0, body.length);
        });
        builder.get("/echo-header", (context, result) -> {
            final NamedValues headers = context.headers();
            final String token = headers
                    .valueRequired("X-Test-Token");
            final byte[] body = token
                    .getBytes(StandardCharsets.UTF_8);
            result.ok(HttpHeaderValues.TEXT_PLAIN,
                    body, 0, body.length);
        });
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

    @Test
    public void testGetJsonWithPathParameter() throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort() + "/v1/hello/NeWA"
        );
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertTrue(response.body().contains("Hello NeWA"));
    }

    @Test
    public void testGetPlainText() throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort() + "/v1/ping"
        );
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("pong", response.body());
    }

    @Test
    public void testQueryParameters() throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort()
                        + "/v1/echo-query?greeting=Hi&name=World"
        );
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("Hi World", response.body());
    }

    @Test
    public void testFormParameters() throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort()
                        + "/v1/echo-form"
        );
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type",
                        "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers
                        .ofString("color=blue"))
                .build();
        final HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("blue", response.body());
    }

    @Test
    public void testHeaders() throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort()
                        + "/v1/echo-header"
        );
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-Test-Token", "secret123")
                .GET()
                .build();
        final HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("secret123", response.body());
    }
}
