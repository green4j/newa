package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * The handler in a pipeline as an application would put it: in front of the one which answers everything
 * else. What is asserted here is what reaches the wire - the bytes of the file, the framing around them, and
 * that a path the handler does not own is still answered by the handler behind it.
 */
class FileServerHandlerTest {
    private static final String HOST = "127.0.0.1";
    private static final int FILE_SIZE = 1024 * 1024 + 7; // not a whole number of chunks

    @TempDir
    private Path root;

    private byte[] content;
    private Path nested;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private HttpClient httpClient;
    private boolean compressing;
    private boolean compressingBehindFiles;
    private final AtomicInteger regionsWritten = new AtomicInteger();

    private static byte[] contentOf(final int size) {
        final byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            result[i] = (byte) (i * 31 + (i >> 8));
        }
        return result;
    }

    private static RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "file-server-test", "file server tests", 1, "test-build");
        builder.getTxt("/hello", (context, output) -> output.appendln("hello"));
        return builder.build();
    }

    private FileSet buildFileSet() {
        return FileSet.builder()
                .serve("/files", root, PathMask.excluding("internal/**"))
                .file("/download/report.bin", nested)
                .index("index.html")
                .build();
    }

    private void initPipeline(final ChannelPipeline pipeline,
                              final RestApi api) {
        // right at the socket, where what the transport is asked to write is what it was really asked to
        // write: a region here is sendfile(2), and a buffer is the file having been read into the process
        pipeline.addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx,
                              final Object msg,
                              final ChannelPromise promise) throws Exception {
                if (msg instanceof FileRegion) {
                    regionsWritten.incrementAndGet();
                }
                super.write(ctx, msg, promise);
            }
        });
        pipeline.addLast(new HttpServerCodec());
        if (compressing) {
            pipeline.addLast(new HttpContentCompressor());
        }
        pipeline.addLast(new HttpObjectAggregator(65536, true));
        pipeline.addLast(new FileServerHandler(buildFileSet()));
        if (compressingBehindFiles) {
            // behind the file handler, so it compresses what the API answers and never sees a file
            pipeline.addLast(new HttpContentCompressor());
        }
        pipeline.addLast(new RestApiHandler(api, new JsonErrorHandler(),
                (channel, cause) -> {
                    throw new AssertionError(cause);
                }));
    }

    @BeforeEach
    public void setUp() throws Exception {
        content = contentOf(FILE_SIZE);

        Files.createDirectories(root.resolve("img"));
        Files.createDirectories(root.resolve("internal"));
        nested = root.resolve("img/big.bin");
        Files.write(nested, content);
        Files.write(root.resolve("index.html"), "<html/>".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("empty.txt"), new byte[0]);
        Files.write(root.resolve("internal/secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));

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

    private void restart() throws Exception {
        tearDown();
        setUp();
    }

    private int serverPort() {
        final InetSocketAddress local = (InetSocketAddress) serverChannel.localAddress();
        return local.getPort();
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder(URI.create("http://" + HOST + ':' + serverPort() + path));
    }

    private HttpResponse<byte[]> get(final String path) throws Exception {
        return httpClient.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(final HttpResponse<?> response,
                                 final String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    @Test
    public void testTheWholeFile() throws Exception {
        final HttpResponse<byte[]> response = get("/files/img/big.bin");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertArrayEquals(content, response.body());
        Assertions.assertEquals(String.valueOf(FILE_SIZE), header(response, "Content-Length"));
        Assertions.assertEquals("bytes", header(response, "Accept-Ranges"));
        Assertions.assertEquals("application/octet-stream", header(response, "Content-Type"));
        Assertions.assertNull(header(response, "Transfer-Encoding"), "the length is known, so it is sent");
        Assertions.assertNotNull(header(response, "Last-Modified"));
        Assertions.assertEquals(1, regionsWritten.get(),
                "a plain socket with nothing in the way of the bytes is exactly where sendfile(2) is used");
    }

    @Test
    public void testTheConnectionIsStillUsableAfterAFile() throws Exception {
        Assertions.assertArrayEquals(content, get("/files/img/big.bin").body());
        Assertions.assertArrayEquals(content, get("/files/img/big.bin").body());
        Assertions.assertEquals(200, get("/v1/hello").statusCode(),
                "the encoder has to have been left able to frame the next response");
    }

    @Test
    public void testContentTypeComesFromTheName() throws Exception {
        Assertions.assertEquals("text/html", header(get("/files/index.html"), "Content-Type"));
    }

    @Test
    public void testAnExactlyNamedFile() throws Exception {
        final HttpResponse<byte[]> response = get("/download/report.bin");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertArrayEquals(content, response.body());

        Assertions.assertEquals(404, get("/download/report.bin/more").statusCode(),
                "a file has nothing under it");
    }

    @Test
    public void testADirectoryIsAnsweredWithItsIndex() throws Exception {
        final HttpResponse<byte[]> response = get("/files");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("<html/>", new String(response.body(), StandardCharsets.UTF_8));
        Assertions.assertEquals(200, get("/files/").statusCode());
    }

    @Test
    public void testHead() throws Exception {
        final HttpResponse<byte[]> response = httpClient.send(
                request("/files/img/big.bin").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(0, response.body().length);
        Assertions.assertEquals(String.valueOf(FILE_SIZE), header(response, "Content-Length"));
    }

    @Test
    public void testARange() throws Exception {
        final HttpResponse<byte[]> response = httpClient.send(
                request("/files/img/big.bin").header("Range", "bytes=1000-1999").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(206, response.statusCode());
        Assertions.assertEquals("bytes 1000-1999/" + FILE_SIZE, header(response, "Content-Range"));
        Assertions.assertEquals("1000", header(response, "Content-Length"));

        final byte[] expected = new byte[1000];
        System.arraycopy(content, 1000, expected, 0, 1000);
        Assertions.assertArrayEquals(expected, response.body());
    }

    @Test
    public void testARangeWhichCannotBeSatisfied() throws Exception {
        final HttpResponse<byte[]> response = httpClient.send(
                request("/files/img/big.bin").header("Range", "bytes=" + FILE_SIZE + "-").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(416, response.statusCode());
        Assertions.assertEquals("bytes */" + FILE_SIZE, header(response, "Content-Range"));
    }

    @Test
    public void testAFileWhichHasNotChanged() throws Exception {
        final String since = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusHours(1));
        final HttpResponse<byte[]> response = httpClient.send(
                request("/files/index.html").header("If-Modified-Since", since).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(304, response.statusCode());
        Assertions.assertEquals(0, response.body().length);
    }

    @Test
    public void testAnEmptyFile() throws Exception {
        final HttpResponse<byte[]> response = get("/files/empty.txt");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(0, response.body().length);
        Assertions.assertEquals("0", header(response, "Content-Length"));
        Assertions.assertEquals(200, get("/v1/hello").statusCode(), "and the connection is still usable");
    }

    @Test
    public void testWhatIsRefused() throws Exception {
        Assertions.assertEquals(404, get("/files/missing.txt").statusCode());
        Assertions.assertEquals(404, get("/files/../../etc/passwd").statusCode(), "outside the root");
        Assertions.assertEquals(404, get("/files/%2e%2e/%2e%2e/etc/passwd").statusCode(), "encoded as well");
        Assertions.assertEquals(404, get("/files/internal/secret.txt").statusCode(), "kept out by a filter");
        Assertions.assertEquals(404, get("/files/img").statusCode(), "a directory with no index in it");

        final HttpResponse<byte[]> posted = httpClient.send(
                request("/files/img/big.bin").POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(405, posted.statusCode());
        Assertions.assertEquals("GET, HEAD", header(posted, "Allow"));
    }

    @Test
    public void testWhatThisHandlerDoesNotOwnGoesOnDownThePipeline() throws Exception {
        final HttpResponse<byte[]> response = get("/v1/hello");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("hello", new String(response.body(), StandardCharsets.UTF_8).trim());
        Assertions.assertEquals(404, get("/nothing/here").statusCode(),
                "and is answered by whatever is behind, not by this handler");
    }

    @Test
    public void testAPipelineWhichCannotSendTheFileFromThePageCache() throws Exception {
        compressing = true;
        restart();

        Assertions.assertArrayEquals(content, get("/files/img/big.bin").body(),
                "pumped through NIO instead, and the same bytes come out");
        Assertions.assertEquals(0, regionsWritten.get(),
                "a region would have gone past the encoder without it ever seeing the bytes");

        final HttpResponse<InputStream> zipped = httpClient.send(
                request("/files/index.html").header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Assertions.assertEquals(200, zipped.statusCode());
        Assertions.assertEquals("gzip", header(zipped, "Content-Encoding"),
                "the encoder saw the body, which is the whole reason a region was not used");
        Assertions.assertEquals("<html/>", new String(unzip(zipped.body()), StandardCharsets.UTF_8));
    }

    @Test
    public void testFilesAndTheApiInOnePipeline() throws Exception {
        compressingBehindFiles = true;
        restart();

        Assertions.assertArrayEquals(content, get("/files/img/big.bin").body());
        Assertions.assertEquals(1, regionsWritten.get(),
                "a compressor behind this handler never sees a file, so it costs the files nothing");

        final HttpResponse<InputStream> zipped = httpClient.send(
                request("/v1/hello").header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Assertions.assertEquals(200, zipped.statusCode());
        Assertions.assertEquals("gzip", header(zipped, "Content-Encoding"),
                "while what the API answers is still compressed");
        Assertions.assertEquals("hello", new String(unzip(zipped.body()), StandardCharsets.UTF_8).trim());
    }

    private static byte[] unzip(final InputStream stream) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(stream)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
