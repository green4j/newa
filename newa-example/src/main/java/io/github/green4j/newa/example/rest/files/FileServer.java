package io.github.green4j.newa.example.rest.files;

import io.github.green4j.newa.lang.Work;
import io.github.green4j.newa.lang.Worker;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.rest.files.PathMask;
import io.github.green4j.newa.rest.handles.Json_Help;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Files served straight from the page cache, with the REST API behind them answering everything the file
 * server does not own.
 * <pre>
 * curl -sD- http://127.0.0.1:9012/files/                    # the index of the root
 * curl -sD- -o /tmp/big.bin http://127.0.0.1:9012/files/img/big.bin
 * curl -sD- -r 100-199 -o /tmp/part.bin http://127.0.0.1:9012/files/img/big.bin   # 206 + Content-Range
 * curl -sD- -r 99999999- -o /dev/null http://127.0.0.1:9012/files/img/big.bin     # 416
 * curl -sD- -o /dev/null http://127.0.0.1:9012/files/internal/secret.txt          # 404, a filter keeps it out
 * curl -sD- -o /dev/null "http://127.0.0.1:9012/files/../../etc/passwd"           # 404
 * curl -sD- http://127.0.0.1:9012/download/report.bin       # the file named at configuration time
 * curl -sD- http://127.0.0.1:9012/v1/zero-copy              # whether sendfile(2) is carrying them
 * curl -sD- http://127.0.0.1:9012/v1/hello/world            # still routed by the REST API
 * </pre>
 * That last two are the point of the example: the file handler takes what it owns and passes on what it does
 * not, and it answers what the pipeline it was put in allows - put an {@code HttpContentCompressor} in front
 * of it and {@code /v1/zero-copy} says so.
 */
public class FileServer {
    public static final String API_NAME = "File API";
    public static final String API_DESCRIPTION = "My File Server";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9012;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    private static final int BIG_FILE_SIZE = 4 * 1024 * 1024;

    public static void main(final String[] args) throws Exception {
        final Path root = createContent();

        final FileSet files = buildFileSet(root);

        final RestApi api = buildApi();

        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // what a file pumped through NIO is paced by: past the high mark the channel reports itself
                // unwritable and nothing more is read from the file until it drains
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), files, api);
                    }
                });

        final Worker worker = new Worker();

        worker.doWork(new Work() {
            @Override
            public ChannelFuture doWork() throws Exception {
                final ChannelFuture bindFuture = bootstrap.bind(
                        InetAddress.getByName(LOCAL_IFC), PORT).sync();

                System.out.printf("Server started and listening on %s. Files are served from %s%n",
                        LOCAL_SERVER_ADDRESS, root);

                return bindFuture.channel().closeFuture();
            }

            @Override
            public void close() {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        });

        System.out.println("Server stopped");
    }

    private static FileSet buildFileSet(final Path root) {
        return FileSet.builder()
                .serve("/files", root, PathMask.excluding("internal/**"))
                .file("/download/report.bin", root.resolve("img/big.bin"))
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

        return apiBuilder.buildWithHelp(Json_Help.factory());
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final FileSet files,
                                     final RestApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        // it takes the paths of the file set and passes on everything else, so what is behind it never sees
        // a request for a file
        pipeline.addLast(new FileServerHandler(files));
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        (channel, cause) -> System.err.printf(
                                "An error %s in the channel: %s%n", cause.getMessage(), channel)
                )
        );
    }

    private static Path createContent() throws IOException {
        final Path root = Files.createTempDirectory("newa-files-example");
        root.toFile().deleteOnExit();

        Files.write(root.resolve("index.html"),
                "<html><body><a href=\"img/big.bin\">big.bin</a></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(root.resolve("img"));
        final byte[] big = new byte[BIG_FILE_SIZE];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        Files.write(root.resolve("img/big.bin"), big);

        Files.createDirectories(root.resolve("internal"));
        Files.write(root.resolve("internal/secret.txt"),
                "not for the wire".getBytes(StandardCharsets.UTF_8));

        return root;
    }
}
