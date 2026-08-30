package io.github.green4j.newa.example.rest.chunked;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.example.rest.SoutRestApiObserver;
import io.github.green4j.newa.lang.Work;
import io.github.green4j.newa.lang.Worker;
import io.github.green4j.newa.rest.ChunkedJsonRestHandle;
import io.github.green4j.newa.rest.ChunkedJsonRestHandler;
import io.github.green4j.newa.rest.ChunkedRestHandle;
import io.github.green4j.newa.rest.ChunkedRestHandler;
import io.github.green4j.newa.rest.ChunkedTxtRestHandle;
import io.github.green4j.newa.rest.ChunkedTxtRestHandler;
import io.github.green4j.newa.rest.ContentDisposition;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.rest.ResponseChunks;
import io.github.green4j.newa.rest.handles.Json_Help;
import io.github.green4j.newa.text.LineAppendable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_DISPOSITION;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.zip.GZIPOutputStream;

/**
 * A response of unbounded size, sent as chunks pulled from a cursor.
 * <p>
 * Nothing here ever holds the whole response: the cursor is stepped only as far as the peer keeps up, and
 * everything runs on the event loop, so a client which stops reading costs one suspended cursor and nothing
 * else - no thread, and no limit on how many other such responses can be in flight.
 * <p>
 * Try it:
 * <pre>
 *   curl -N 'http://127.0.0.1:9010/v1/rows/1000000'   # a million rows, at whatever rate you take them
 *   curl -OJ 'http://127.0.0.1:9010/v1/download/1000000'   # the same, gzipped as it goes
 *   curl -s 'http://127.0.0.1:9010/v1/cursors'        # how many cursors are open right now
 * </pre>
 * Start four downloads at once and the fifth is answered {@code 503}: {@link ResponseChunks#maxOpenCursors()}
 * is set to four below. Suspend one and watch {@code /v1/cursors}: it stays taken until the response finishes
 * or the watchdog gives up on it. Every one of those moments is printed by the observer.
 */
public class ChunkedRestServer {
    public static final String API_NAME = "Chunked API";
    public static final String API_DESCRIPTION = "Responses pulled from a cursor";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    /** How many rows one step hands over, and so how much of the collection is ever in memory at once. */
    private static final int BATCH = 500;

    /** Netty has no constant for it, and a content type is compared often enough to be worth interning. */
    private static final AsciiString APPLICATION_GZIP = AsciiString.cached("application/gzip");

    /** Built once here rather than per request: the name does not depend on which request asked for it. */
    private static final AsciiString GZ_ATTACHMENT = ContentDisposition.attachment("rows.json.gz");

    /** Everything about chunked responses, decided once, here. */
    private static final ResponseChunks CHUNKS = ResponseChunks.builder()
            .size(64 * 1024)
            .stallTimeoutMillis(10_000)
            .maxOpenCursors(4)
            .build();

    /**
     * Stands in for a database cursor: it holds a resource for as long as it is open, produces rows in
     * batches, and has to be closed whatever happens to the response.
     */
    private static final class RowCursor implements ChunkedJsonRestHandle.Cursor {
        private final int rows;
        private int next;
        private boolean started;

        private RowCursor(final int rows) {
            this.rows = rows;
        }

        @Override
        public boolean writeNext(final JsonGenerator output) {
            if (!started) {
                started = true;
                output.startArray(); // left open: the framework ends the document
            }
            final int until = Math.min(rows, next + BATCH);
            while (next < until) {
                output.startObject();
                output.objectMember("id");
                output.numberValue(next);
                output.objectMember("name");
                output.stringValue("row-" + next);
                output.endObject();
                next++;
            }
            return next < rows;
        }

        @Override
        public void close() {
        }
    }

    /** The same thing in plain text, to show the other handle. */
    private static final class LineCursor implements ChunkedTxtRestHandle.Cursor {
        private final int lines;
        private int next;

        private LineCursor(final int lines) {
            this.lines = lines;
        }

        @Override
        public boolean writeNext(final LineAppendable output) {
            final int until = Math.min(lines, next + BATCH);
            while (next < until) {
                output.appendln("row-" + next++);
            }
            return next < lines;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Bytes rather than characters: this cursor writes into the chunk itself, and what it writes is gzip.
     * <p>
     * Nothing is held in full - not the rows, not the compressed bytes. A step hands a batch of rows to the
     * deflater, and whatever the deflater has finished compressing by then lands straight in the chunk; the
     * rest stays in its 512-byte buffer until the next step. So a gigabyte download costs the same as a
     * kilobyte one, and the client gets a file it can decompress while it is still arriving.
     */
    private static final class GzipRowCursor implements ChunkedRestHandle.Cursor {
        /**
         * The chunk being filled. The stream is made once per response and the header goes in whichever chunk
         * is current at the time, so the target is swapped in before every step rather than fixed at
         * construction.
         */
        private static final class ChunkOutput extends OutputStream {
            private ByteBuf chunk;

            @Override
            public void write(final int b) {
                if (chunk != null) {
                    chunk.writeByte(b);
                }
            }

            @Override
            public void write(final byte[] bytes,
                              final int offset,
                              final int length) {
                if (chunk == null) {
                    // no step is in progress, so this is close() on a response which was abandoned, and what
                    // it is trying to write is a trailer nobody will ever read
                    return;
                }
                chunk.writeBytes(bytes, offset, length);
            }
        }

        private final ChunkOutput output = new ChunkOutput();

        // a row is built into these two and copied out, so stepping the cursor allocates nothing
        private final StringBuilder line = new StringBuilder(64);
        private byte[] ascii = new byte[64];

        private final int rows;

        private GZIPOutputStream gzip;
        private int next;

        private GzipRowCursor(final int rows) {
            this.rows = rows;
        }

        @Override
        public boolean writeNext(final ByteBuf chunk) {
            output.chunk = chunk;
            try {
                if (gzip == null) {
                    // writes the gzip header at once, which is why it waits for a chunk to write it into
                    gzip = new GZIPOutputStream(output);
                }

                final int until = Math.min(rows, next + BATCH);
                while (next < until) {
                    writeRow(next++);
                }

                if (next < rows) {
                    return true;
                }

                gzip.finish(); // the trailer, and whatever the deflater was still holding on to
                return false;
            } catch (final IOException e) {
                // the sink is a buffer, so there is no I/O here to fail - this can only be a bug
                throw new IllegalStateException("Failed to compress the response", e);
            } finally {
                // the chunk belongs to the channel from here on
                output.chunk = null;
            }
        }

        private void writeRow(final int id) throws IOException {
            line.setLength(0);
            line.append("{\"id\":").append(id).append(",\"name\":\"row-").append(id).append("\"}\n");

            final int length = line.length();
            if (ascii.length < length) {
                ascii = new byte[length];
            }
            for (int i = 0; i < length; i++) {
                ascii[i] = (byte) line.charAt(i);
            }

            gzip.write(ascii, 0, length);
        }

        @Override
        public void close() {
            if (gzip == null) {
                return;
            }
            try {
                // the deflater holds zlib state outside the heap, and this is what gives it back. Leaving it
                // to the garbage collector would be the one thing a cursor must never do with a resource -
                // and an abandoned download gets here without ever having finished the document
                gzip.close();
            } catch (final IOException ignored) {
                // there is nothing left to report it to: the response is over either way
            }
        }
    }

    private static RestApi buildApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        // opening the cursor is where the request is validated: nothing has been sent yet, so anything
        // thrown here still becomes an ordinary error response
        apiBuilder.get("/rows/{count}", new ChunkedJsonRestHandler(context ->
                new RowCursor(context.pathParameters().valueRequiredAsInt("count")))
        ).withPathParameterDescriptions("count - How many rows to send")
                .withDescription("A JSON array of that many rows, pulled from a cursor.");

        apiBuilder.get("/lines/{count}", new ChunkedTxtRestHandler(context ->
                new LineCursor(context.pathParameters().valueRequiredAsInt("count")))
        ).withPathParameterDescriptions("count - How many lines to send")
                .withDescription("The same, as plain text.");

        apiBuilder.get("/download/{count}", new ChunkedRestHandler(APPLICATION_GZIP, context -> {
            // where any handler puts an application header, pre-built or not
            context.responseHeaders().set(CONTENT_DISPOSITION, GZ_ATTACHMENT);
            return new GzipRowCursor(context.pathParameters().valueRequiredAsInt("count"));
        })).withPathParameterDescriptions("count - How many rows to compress")
                .withDescription("The same rows as NDJSON, gzipped a batch at a time as they go out.");

        apiBuilder.getJson("/cursors", (context, output) ->
                output.numberValue(CHUNKS.openCursors())
        ).withDescription("How many cursors are open right now. Must return to zero.");

        apiBuilder.getJson("/cursors/limit", (context, output) ->
                output.numberValue(CHUNKS.maxOpenCursors())
        ).withDescription("How many may be open at once before requests are refused with 503.");

        return apiBuilder.buildWithHelp(Json_Help.factory());
    }

    private static void initPipeline(final ChannelPipeline pipeline,
                                     final RestApi api) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(
                65536,
                true
        ));
        // ChunkedWriteHandler is not installed here: the first chunked response puts it in front of this one
        pipeline.addLast(
                new RestApiHandler(
                        api,
                        new JsonErrorHandler(),
                        (channel, cause) -> System.err.printf(
                                "An error %s in the channel: %s%n", cause.getMessage(), channel),
                        CHUNKS,
                        SoutRestApiObserver.factory()
                )
        );
    }

    public static void main(final String[] args) throws Exception {
        final Worker worker = new Worker();
        final RestApi api = buildApi();

        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // this is where the backpressure comes from: past the high mark the channel reports itself
                // unwritable and the cursor stops being stepped until it drains
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        initPipeline(ch.pipeline(), api);
                    }
                });

        worker.doWork(new Work() {
            @Override
            public ChannelFuture doWork() throws Exception {
                final ChannelFuture bindFuture = bootstrap.bind(
                        InetAddress.getByName(LOCAL_IFC), PORT).sync();

                System.out.printf(
                        "Server started and listening on %s. Help is available on %s%s%n",
                        LOCAL_SERVER_ADDRESS,
                        LOCAL_SERVER_ADDRESS,
                        api.helpPath()
                );

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
}
