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

package io.github.green4j.newa.example.files;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.files.FileServer;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.rest.files.PathMask;
import io.github.green4j.newa.rest.handles.JsonHelp;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Files served straight from the page cache, with a small REST API sharing the port and answering
 * everything the files do not own.
 * <pre>
 * curl -sD- http://127.0.0.1:9012/files/                    # the index of the root
 * curl -sD- -o /tmp/big.bin http://127.0.0.1:9012/files/img/big.bin
 * curl -sD- -r 100-199 -o /tmp/part.bin http://127.0.0.1:9012/files/img/big.bin   # 206 + Content-Range
 * curl -sD- -r 99999999- -o /dev/null http://127.0.0.1:9012/files/img/big.bin     # 416
 * curl -sD- -o /dev/null http://127.0.0.1:9012/files/internal/secret.txt          # 404, a filter keeps it out
 * curl -sD- -o /dev/null "http://127.0.0.1:9012/files/../../etc/passwd"           # 404
 * curl -sD- http://127.0.0.1:9012/download/report.bin       # the file named at configuration time
 * curl -sD- http://127.0.0.1:9012/v1/zero-copy              # whether sendfile(2) is carrying them
 * curl -sD- http://127.0.0.1:9012/v1/hello/world            # answered by the api behind the files
 * curl -sD- http://127.0.0.1:9012/nothing/here              # 404, and the connection is still usable
 * </pre>
 * The composition is the point of it. {@link FileServer} is a file server and nothing else; a
 * {@link RestApiHandler} handed to {@link FileServer#withHandler} goes behind the files, where a request no
 * file owns arrives - the file handler passes on a path it does not own. That is the same shape
 * {@code WsServer.withHandler(() -> new RestApiHandler(...))} has, and the mirror image of a REST server
 * which also serves files, where the {@link FileServerHandler} is what goes into
 * {@code RestServer.withHandler(...)}.
 * <p>
 * {@link FileServer#withCompression()} is deliberately not used here: on this side it would go in front of
 * the file handler - the only place from which a file can be compressed at all - and that costs
 * {@code sendfile(2)}. {@code rest.pipeline.PipelineRestServer} makes exactly that placement by hand and
 * reports {@code false} on the same endpoint.
 * <p>
 * {@link FileServer#withCors} is here, in front of the files, so a page on the allowed origin may read them
 * as well as the api:
 * <pre>
 * curl -sD- -H 'Origin: https://app.example.com' http://127.0.0.1:9012/files/index.html
 * </pre>
 */
public class SimpleFileServer {
    public static final String API_NAME = "File API";
    public static final String API_DESCRIPTION = "My File Server";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9012;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    private static final int BIG_FILE_SIZE = 4 * 1024 * 1024;

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    public static void main(final String[] args) throws Exception {
        final Path root = createContent();

        final FileSet files = buildFileSet(root);

        final RestApi api = buildApi();

        // one error handler and one channel error handler for both halves: the api handler is built here,
        // so it is this code which hands it what the file server would otherwise have given it alone
        final ChannelErrorHandler channelErrors = new StdErrChannelErrorHandler();
        final JsonErrorHandler errors = new JsonErrorHandler();

        // the water marks are what a file pumped through NIO is paced by: past the high mark the channel
        // reports itself unwritable and nothing more is read from the file until it drains. FileServer
        // takes the defaults, which are the 32K/64K this example used to set by hand.
        new Life().run(() -> {
            final NettyServer server = FileServer.of(files)
                    // behind the files, which is where a request no file owns arrives
                    .withHandler(() -> new RestApiHandler(api, errors, channelErrors))
                    .withChannelErrorHandler(channelErrors)
                    .withErrorHandler(errors)
                    // and this goes in front of the files, so a browser on another origin may read them
                    // as well as the api. Nothing is added without it
                    .withCors(CorsConfigBuilder.forOrigin(ALLOWED_ORIGIN)
                            .allowedRequestMethods(HttpMethod.GET, HttpMethod.HEAD)
                            .build())
                    // nothing here reads a file - sendfile(2) carries them all, as /v1/zero-copy reports.
                    // Put a compressor or TLS in front of them and it cannot, and then this is what keeps
                    // the reading off the event loop, one chunk read ahead of the one being written:
                    // .withReadExecutor(Executors.newFixedThreadPool(4))
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started and listening on %s. Files are served from %s. Try:%n",
                    LOCAL_SERVER_ADDRESS, root);
            System.out.printf("  curl -sD- %s/files/               -> the index of the root%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- -r 100-199 -o /dev/null %s/files/img/big.bin   -> 206%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- -r 99999999- -o /dev/null %s/files/img/big.bin -> 416%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- -o /dev/null %s/files/internal/secret.txt      -> 404, filtered%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- -o /dev/null \"%s/files/../../etc/passwd\"       -> 404%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- %s/download/report.bin  -> the file named at configuration time%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/zero-copy           -> whether sendfile(2) is carrying them%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -s %s/v1/hello/world         -> the api behind the files%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -sD- %s/nothing/here         -> 404, connection still usable%n",
                    LOCAL_SERVER_ADDRESS);

            return server;
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

        return apiBuilder.buildWithHelp(JsonHelp.factory());
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
