/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.rest.AbstractHttpServer;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentCompressor;

import java.util.concurrent.Executor;

/**
 * A file server in one line:
 * <pre>{@code
 * new Life().run(() -> FileServer.start(files, 9012));
 * }</pre>
 * and the same thing with an api sharing the port:
 * <pre>{@code
 * FileServer.of(files)
 *         .withHandler(() -> new RestApiHandler(api, errors, channelErrors))
 *         .start(9012);
 * }</pre>
 * <p>
 * It assembles this pipeline, out of the same public handlers a pipeline written by hand is made of:
 * <pre>
 * Client --&gt; [IdleConnectionHandler] --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt;
 *            [RequestDeadlineHandler] --&gt; [ResponseDeadlineHandler] --&gt; DecoderFailureHandler --&gt;
 *            [CorsHandler] --&gt; [HttpContentCompressor] --&gt; FileServerHandler --&gt; [your handlers] --&gt;
 *            FilesOnlyHandler
 * </pre>
 * Nothing is hidden and nothing is one-way: {@link #pipeline()} hands the same initializer to a
 * {@link io.netty.bootstrap.ServerBootstrap} of your own, and everything below the pipeline - the transport,
 * the threads, the channel options - stays on {@link NettyServerBuilder}, which
 * {@link #start(NettyServerBuilder)} takes.
 * <p>
 * What belongs to the files - which paths are served from where, the filters, the index, the content types -
 * is the {@link FileSet}'s, and is not repeated here.
 * <p>
 * The other way round is a REST server which also serves files: put a {@link FileServerHandler} into
 * {@code RestServer.withHandler(...)}, which lands it in front of the api and behind the compressor.
 */
public final class FileServer extends AbstractHttpServer<FileServer> {
    /**
     * The whole server in one call, with everything at its default - which includes the <b>loopback</b>, so
     * nothing outside this machine can reach it. {@link #start(FileSet, String, int)} opens it up.
     *
     * @param files to serve.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final FileSet files,
                                    final int port) throws InterruptedException {
        return of(files).start(port);
    }

    /**
     * The whole server in one call, on an interface of your own: the address of the network it belongs on,
     * or {@link NettyServerBuilder#ANY_HOST} for every interface. The two-argument form leaves it on the
     * loopback.
     *
     * @param files to serve.
     * @param host to bind, or {@link NettyServerBuilder#ANY_HOST} for every interface.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final FileSet files,
                                    final String host,
                                    final int port) throws InterruptedException {
        return of(files).start(host, port);
    }

    /**
     * @param files to serve, from {@link FileSet#builder()}.
     * @return a server to configure and then start.
     */
    public static FileServer of(final FileSet files) {
        return new FileServer(files);
    }

    private final FileSet files;

    private int chunkSize = FileServerHandler.DEFAULT_CHUNK_SIZE;
    private Executor reads;

    private FileServer(final FileSet files) {
        this.files = files;
    }

    /**
     * @param bytes of a file read at a time when it cannot be sent from the page cache,
     *              {@link FileServerHandler#DEFAULT_CHUNK_SIZE} by default. Nothing is read at all while
     *              {@code sendfile(2)} is what carries a file, so this is the size of the fallback and of
     *              nothing else.
     * @return this builder.
     */
    public FileServer withChunkSize(final int bytes) {
        this.chunkSize = bytes;
        return this;
    }

    /**
     * Moves the reading of a file off the event loop, which matters exactly where {@link #withChunkSize(int)}
     * does: while {@code sendfile(2)} carries a file nothing is read at all, and where it cannot - TLS, a
     * compressor in front of the files, a transport which has no socket under it - every chunk is read by the
     * loop, and a page which is not in the cache is a stall the other connections of that loop pay for.
     *
     * <p>The threads are the caller's, and nothing here shuts them down: put the pool beside the server in
     * {@link io.github.green4j.newa.lang.Life#all} and it ends when the server does. One chunk is read ahead
     * per response being pumped, and a thread is held for one read rather than for a transfer, so the pool
     * bounds the reads in flight and not the downloads.
     *
     * <p>Left alone the file is read by the event loop, which is the right answer for the assets of a page:
     * they sit in the page cache, where a read is a memcpy and the hop to another thread would cost more
     * than the read.
     *
     * @param reads the files are read on, or null for the event loop.
     * @return this builder.
     */
    public FileServer withReadExecutor(final Executor reads) {
        this.reads = reads;
        return this;
    }

    /**
     * The file handler, with everything which decides how a file is written in front of it and everything
     * which answers what the files do not own behind it.
     *
     * <p>{@link #withCompression()} lands in front, which is the only place from which a file can be
     * compressed at all - a response travels towards the head of the pipeline, so a compressor behind the
     * file handler would never see one. It costs {@code sendfile(2)}: a compressor has to read the bytes,
     * and a file which is read is a file which entered the process. Text gains more than it loses there and
     * an archive or a video loses outright, so it is a decision and not a default -
     * {@link FileServerHandler#zeroCopySupported(io.netty.channel.Channel)} reports which of the two this
     * pipeline ended up with.
     *
     * <p>{@link #withHandler(java.util.function.Supplier)} lands behind, which is where a request no file
     * owns arrives: the file handler passes on a path it does not own. That is how one port serves both the
     * files and an HTTP api - put a {@code RestApiHandler} here.
     *
     * <p>A {@link FilesOnlyHandler} stands behind whatever is added there, so a request neither the files
     * nor any of them took is answered {@code 404} rather than holding a connection open unanswered.
     *
     * @param pipeline of one accepted channel.
     */
    @Override
    protected void initTail(final ChannelPipeline pipeline) {
        if (compression()) {
            pipeline.addLast(new HttpContentCompressor());
        }

        pipeline.addLast(
                new FileServerHandler(
                        files,
                        errorHandler(),
                        channelErrorHandler(),
                        observers(),
                        chunkSize,
                        reads
                )
        );

        addHandlers(pipeline);

        // last, so everything added above answers first. Without it a request no file owns would sit at the
        // end of the pipeline, discarded in silence, holding a connection which nothing is on a timer to
        // close
        pipeline.addLast(new FilesOnlyHandler(errorHandler(), observers()));
    }
}
