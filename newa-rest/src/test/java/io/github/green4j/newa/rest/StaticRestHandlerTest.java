/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.AsciiString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The bytes a static handler puts on the wire must be decided by the charset it was given, never by the
 * default of the machine running the server. The expectations here are written out byte by byte on purpose:
 * deriving them with the same getBytes() call the handler makes would assert nothing.
 */
class StaticRestHandlerTest {
    private static final String HOST = "127.0.0.1";

    // "hello" with an acute e - five characters, six bytes of UTF-8
    private static final String TEXT = "h\u00e9llo";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "static-handler-test",
                "static handler tests",
                1,
                "test-build"
        );
        builder.get("/txt-default", StaticRestHandler.txt(TEXT));
        builder.get("/txt-ascii", StaticRestHandler.txt(TEXT, Charset.US_ASCII));
        builder.get("/txt-utf8", StaticRestHandler.txt(TEXT, Charset.UTF8));
        builder.get("/json-default", StaticRestHandler.json(TEXT));
        builder.get("/json-ascii", StaticRestHandler.json(TEXT, Charset.US_ASCII));
        builder.get("/raw", new StaticRestHandler(
                AsciiString.cached("text/html; charset=utf-8"),
                new byte[] {'h', (byte) 0xC3, (byte) 0xA9, 'l', 'l', 'o'}));
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

    private HttpResponse<byte[]> get(final String path) throws Exception {
        final URI uri = URI.create(
                "http://" + HOST + ':' + serverPort() + "/v1" + path
        );
        final HttpResponse<byte[]> response = httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        Assertions.assertEquals(200, response.statusCode());
        return response;
    }

    private byte[] body(final String path) throws Exception {
        return get(path).body();
    }

    private String contentType(final String path) throws Exception {
        return get(path).headers().firstValue("Content-Type").orElse(null);
    }

    /**
     * @param path to ask for.
     * @param hex  the bytes the content is expected to arrive as. A character the charset cannot hold is
     *             replaced with a question mark rather than reported, which is what 3f is here.
     */
    @ParameterizedTest
    @CsvSource({
        "/txt-default,   683f6c6c6f",   // text is ASCII unless something else is asked for
        "/txt-ascii,     683f6c6c6f",
        "/txt-utf8,      68c3a96c6c6f",
        "/json-default,  68c3a96c6c6f", // RFC 8259 puts JSON on the wire as UTF-8
        "/json-ascii,    683f6c6c6f",
        "/raw,           68c3a96c6c6f"  // the bytes the caller gave, sent as they were given
    })
    public void theContentIsRenderedInTheCharsetItIsSentIn(final String path,
                                                           final String hex) throws Exception {
        final byte[] expected = new byte[hex.length() / 2];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }

        Assertions.assertArrayEquals(expected, body(path), path);
    }

    @ParameterizedTest
    @CsvSource({
        "/txt-default,   text/plain; charset=us-ascii",
        "/txt-ascii,     text/plain; charset=us-ascii",
        "/txt-utf8,      text/plain; charset=utf-8",
        "/json-default,  application/json; charset=utf-8",
        "/json-ascii,    application/json; charset=us-ascii",
        // and the caller owns the header when it owns the bytes
        "/raw,           text/html; charset=utf-8"
    })
    public void theCharsetTheContentWasRenderedWithIsNamedInTheContentType(final String path,
                                                                          final String expected)
            throws Exception {
        Assertions.assertEquals(expected, contentType(path), path);
    }
}
