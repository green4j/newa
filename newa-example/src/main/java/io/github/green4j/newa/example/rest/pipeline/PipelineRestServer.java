/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.rest.pipeline;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.HttpErrorHandler;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.rest.handles.JsonHelp;
import io.github.green4j.newa.server.DecoderFailureHandler;
import io.github.green4j.newa.server.IdleConnectionHandler;
import io.github.green4j.newa.server.RequestDeadlineHandler;
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
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The same server {@code files.SimpleFileServer} is, with the bootstrap and the pipeline written out by
 * hand - which is what you reach for once the pipeline itself is what needs changing. Every other example
 * in this module leaves both to {@link RestServer}.
 * <pre>
 * curl -sD- http://127.0.0.1:9013/v1/zero-copy     # "files are pumped through NIO" - see below
 * curl -sD- http://127.0.0.1:9013/files/big.bin -o /dev/null
 * curl -sD- http://127.0.0.1:9013/v1/hello/world
 * </pre>
 * Three things here are not expressible through {@link RestServer}, and each is the reason someone would
 * write this out:
 * <ul>
 *   <li>the transport and the event loop groups, chosen and sized directly rather than through
 *       {@link io.github.green4j.newa.server.NettyServerBuilder};</li>
 *   <li>{@link ChannelOption#SO_BACKLOG}, and water marks picked for what this server actually sends;</li>
 *   <li>and the one that changes behaviour: the {@link HttpContentCompressor} sits <b>in front of</b> the
 *       {@link FileServerHandler}. Neither helper will make that placement - {@code RestServer.withHandler}
 *       lands a file handler in front of the compressor, where {@code sendfile(2)} survives, and
 *       {@code FileServer} puts the compressor in front knowingly and says what it costs. In front of it,
 *       every file is read into the process instead - which is what {@code /v1/zero-copy} reports here, and
 *       nothing else would have told you. Compare it against {@code SimpleFileServer} on port 9012, which
 *       answers the opposite.</li>
 * </ul>
 * The {@link HttpServerCodec} limits are no longer one of them: {@code RestServer.withMaxInitialLineLength}
 * and {@code withMaxHeaderSize} reach exactly these two numbers, which are Netty's own defaults written out.
 * Neither the {@link CorsHandler} nor the {@link IdleConnectionHandler} is one either -
 * {@code RestServer.withCors} and {@code RestServer.withIdleTimeoutMs} put both in exactly these places,
 * the second of them by default. They are written out here because the placement is the whole of it:
 * <ul>
 *   <li>the CORS handler goes in front of the {@link FileServerHandler}, so a file carries the
 *       {@code Access-Control-} headers too. Behind it, an api response would carry them and every file
 *       would silently not;</li>
 *   <li>the idle handler goes first of all, in front of the codec: what it measures is traffic, not
 *       messages, and a decoder still waiting for the rest of one has nothing to hand on;</li>
 *   <li>the {@link DecoderFailureHandler} goes in front of everything which answers, so that a request line
 *       or a header block past the limits above is answered {@code 414} or {@code 431} rather than reaching
 *       the api as the substitute request the decoder emits and being answered {@code 404}.</li>
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
    private static final int MAX_INITIAL_LINE_BYTES = 4096;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int WATER_MARK_LOW = 32 * 1024;
    private static final int WATER_MARK_HIGH = 128 * 1024;
    private static final int BACKLOG = 1024;
    private static final int IDLE_SECONDS = 60;

    /** Half of it, in each direction: what a connection which is doing something is given to do it. */
    private static final int DEADLINE_SECONDS = 30;
    private static final int BIG_FILE_SIZE = 4 * 1024 * 1024;

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

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

            System.out.printf("Server started and listening on %s. Files are served from %s. Try:%n",
                    LOCAL_SERVER_ADDRESS, root);
            System.out.printf("  curl -s %s/v1/zero-copy    -> false here: a compressor stands in front%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- -o /dev/null %s/files/big.bin%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/hello/world%n", LOCAL_SERVER_ADDRESS);

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
        // this is the only thing which will ever take it back. Note which handler this is - Netty's own
        // IdleStateHandler fires an event and closes nothing, so a pipeline with one and no handler for
        // that event holds the connection exactly as long as it would have without it
        pipeline.addLast(new IdleConnectionHandler(IDLE_SECONDS * 1000L));

        // the request line and the header block, written out rather than inherited. Netty's defaults are
        // these very numbers, and so are RestServer.withMaxInitialLineLength and withMaxHeaderSize - the
        // point of saying them here is that on this side of the fence they are a decision, not an assumption
        pipeline.addLast(new HttpServerCodec(MAX_INITIAL_LINE_BYTES, MAX_HEADER_BYTES, MAX_REQUEST_BYTES));
        pipeline.addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));

        // the two the idle handler above cannot be. It only knows that bytes moved, so a request dribbled in
        // a byte at a time and a response taken a byte at a time both look busy to it; these count what
        // actually arrived, and they go behind the aggregator because that is where a burst of bytes can be
        // told apart from a message
        pipeline.addLast(new RequestDeadlineHandler(DEADLINE_SECONDS * 1000L));
        pipeline.addLast(new ResponseDeadlineHandler(DEADLINE_SECONDS * 1000L));

        // what the codec above refused arrives here as a substitute request - GET /bad-request, carrying the
        // real cause - and would be answered 404 by the api. This turns it into the 414 or the 431 it is,
        // and closes the connection the decoder has already given up on. RestServer puts one in by itself
        pipeline.addLast(new DecoderFailureHandler());

        // in front of the file handler, which is where it has to be for a file to carry the headers too:
        // FileServerHandler writes its response head from its own place in the pipeline, so only a handler
        // nearer the front than it ever sees one. RestServer.withCors puts it here for the same reason
        pipeline.addLast(new CorsHandler(
                CorsConfigBuilder.forOrigin(ALLOWED_ORIGIN)
                        .allowedRequestMethods(HttpMethod.GET, HttpMethod.HEAD)
                        .build()));

        // in front of the file handler on purpose: this is the placement RestServer will not make, and
        // /v1/zero-copy is where its cost shows up
        pipeline.addLast(new HttpContentCompressor());

        // one for both: whichever of them catches a failed channel closes it and reports it exactly once
        final ChannelErrorHandler channelErrors = (channel, cause) -> System.err.printf(
                "An error %s in the channel: %s%n", cause.getMessage(), channel);

        // one for both as well: an error is rendered the same way whichever of them answers it, and this is
        // the object to replace to answer them with pages of your own - see rest.errors.ErrorsRestServer
        final HttpErrorHandler errors = new JsonErrorHandler();

        pipeline.addLast(new FileServerHandler(files, errors, channelErrors, null));

        // no ChunkedWriteHandler: the first response which needs one puts it in front of the handler below
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        errors,
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
