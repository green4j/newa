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

package io.github.green4j.newa.example.rest.files;

import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.rest.files.PathMask;
import io.github.green4j.newa.rest.handles.JsonHelp;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;

import java.io.IOException;
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
 * Those last two are the point of the example: the file handler takes what it owns and passes on what it
 * does not, and it answers what the pipeline it was put in allows.
 * <p>
 * {@link RestServer#withCompression()} would not change that answer - it places the compressor behind the
 * file handler, where it compresses what the api returns and never sees a file. One in <i>front</i> of the
 * file handler costs {@code sendfile(2)}, and that is only reachable by assembling the pipeline yourself:
 * {@code rest.pipeline.PipelineRestServer} does exactly that, and reports {@code false} on the same
 * endpoint.
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

        // the water marks are what a file pumped through NIO is paced by: past the high mark the channel
        // reports itself unwritable and nothing more is read from the file until it drains. RestServer
        // takes the defaults, which are the 32K/64K this example used to set by hand.
        new Life().run(() -> {
            final NettyServer server = RestServer.of(api)
                    .withFiles(files) // in front of the api, which then never sees a request for a file
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started and listening on %s. Files are served from %s%n",
                    LOCAL_SERVER_ADDRESS, root);

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
