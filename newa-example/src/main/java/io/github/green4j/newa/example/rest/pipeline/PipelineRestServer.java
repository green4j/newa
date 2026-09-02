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

package io.github.green4j.newa.example.rest.pipeline;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.rest.handles.JsonHelp;
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
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The same server {@code rest.files.FileServer} is, with the bootstrap and the pipeline written out by
 * hand - which is what you reach for once the pipeline itself is what needs changing. Every other example
 * in this module leaves both to {@link RestServer}.
 * <pre>
 * curl -sD- http://127.0.0.1:9013/v1/zero-copy     # "files are pumped through NIO" - see below
 * curl -sD- http://127.0.0.1:9013/files/big.bin -o /dev/null
 * curl -sD- http://127.0.0.1:9013/v1/hello/world
 * </pre>
 * Four things here are not expressible through {@link RestServer}, and each is the reason someone would
 * write this out:
 * <ul>
 *   <li>the transport and the event loop groups, chosen and sized directly rather than through
 *       {@link io.github.green4j.newa.server.NettyServerBuilder};</li>
 *   <li>{@link ChannelOption#SO_BACKLOG}, and water marks picked for what this server actually sends;</li>
 *   <li>an {@link IdleStateHandler}, which nothing in the library installs for you;</li>
 *   <li>and the one that changes behaviour: the {@link HttpContentCompressor} sits <b>in front of</b> the
 *       {@link FileServerHandler}. {@code RestServer.withCompression()} puts it behind, where it never sees
 *       a file and costs {@code sendfile(2)} nothing. In front of it, every file is read into the process
 *       instead - which is what {@code /v1/zero-copy} reports here, and nothing else would have told you.
 *       Compare it against {@code FileServer} on port 9012, which answers the opposite.</li>
 * </ul>
 */
public class PipelineRestServer {
    public static final String API_NAME = "Pipeline API";
    public static final String API_DESCRIPTION = "A server assembled by hand";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9013;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    private static final int MAX_REQUEST_BYTES = 65536;
    private static final int WATER_MARK_LOW = 32 * 1024;
    private static final int WATER_MARK_HIGH = 128 * 1024;
    private static final int BACKLOG = 1024;
    private static final int IDLE_SECONDS = 60;
    private static final int BIG_FILE_SIZE = 4 * 1024 * 1024;

    public static void main(final String[] args) throws Exception {
        final Path root = createContent();
        final FileSet files = buildFileSet(root);
        final RestApi api = buildApi();

        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(WATER_MARK_LOW, WATER_MARK_HIGH))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), files, api);
                    }
                });

        // the bind belongs inside run(): the server is owned from the instant it can serve, which is what
        // leaves no window between it accepting a request and something being able to end it. What
        // RestServer would have returned is a NettyServer; assembled by hand it is whatever closes what
        // was opened here, and AutoCloseable is a functional interface, so that is one more lambda.
        new Life().run(() -> {
            final Channel serverChannel = bootstrap.bind(
                    InetAddress.getByName(LOCAL_IFC), PORT).sync().channel();

            System.out.printf("Server started and listening on %s. Files are served from %s%n",
                    LOCAL_SERVER_ADDRESS, root);

            return () -> {
                serverChannel.close().awaitUninterruptibly();
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            };
        });

        System.out.println("Server stopped");
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final FileSet files,
                                     final RestApi api) {
        // nothing reads or writes for a minute: a connection nobody is using costs a file descriptor, and
        // this is the only thing which will ever close it
        pipeline.addLast(new IdleStateHandler(IDLE_SECONDS, IDLE_SECONDS, IDLE_SECONDS));

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));

        // in front of the file handler on purpose: this is the placement RestServer will not make, and
        // /v1/zero-copy is where its cost shows up
        pipeline.addLast(new HttpContentCompressor());

        // one for both: whichever of them catches a failed channel closes it and reports it exactly once
        final ChannelErrorHandler channelErrors = (channel, cause) -> System.err.printf(
                "An error %s in the channel: %s%n", cause.getMessage(), channel);

        pipeline.addLast(new FileServerHandler(files, new JsonErrorHandler(), channelErrors, null));

        // no ChunkedWriteHandler: the first response which needs one puts it in front of the handler below
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        channelErrors
                )
        );
    }

    private static FileSet buildFileSet(final Path root) {
        return FileSet.builder()
                .serve("/files", root)
                .index("index.html")
                .build();
    }

    private static RestApi buildApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME, API_DESCRIPTION, API_VERSION, API_BUILD_VERSION);

        apiBuilder.getJson("/hello/{name}",
                (context, output) -> output.stringValue(
                        String.format("Hello %s!", context.pathParameters().valueRequired("name")))
        ).withPathParameterDescriptions("name - Your name");

        apiBuilder.getJson("/zero-copy",
                (context, output) -> output.stringValue(
                        FileServerHandler.zeroCopySupported(context.channel())
                                ? "files are sent with sendfile(2)"
                                : "files are pumped through NIO"));

        return apiBuilder.buildWithHelp(JsonHelp.factory());
    }

    private static Path createContent() throws IOException {
        final Path root = Files.createTempDirectory("newa-pipeline-example");
        root.toFile().deleteOnExit();

        Files.write(root.resolve("index.html"),
                "<html><body><a href=\"big.bin\">big.bin</a></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        final byte[] big = new byte[BIG_FILE_SIZE];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        Files.write(root.resolve("big.bin"), big);

        return root;
    }
}
