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

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.rest.files.FileServerHandler;
import io.github.green4j.newa.rest.files.FileSet;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A REST server in one line:
 * <pre>{@code
 * RestServer.start(9009, api).awaitClose();
 * }</pre>
 * and the same thing with something turned on:
 * <pre>{@code
 * RestServer.of(api)
 *         .withCompression()
 *         .withFiles(files)
 *         .withObservers(observers)
 *         .start(9009);
 * }</pre>
 * <p>
 * It assembles exactly the pipeline this module documents, out of the same public handlers a pipeline
 * written by hand is made of:
 * <pre>
 * Client --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt; [FileServerHandler] --&gt;
 *            [your handlers] --&gt; [HttpContentCompressor] --&gt; RestApiHandler
 * </pre>
 * Nothing is hidden and nothing is one-way: {@link #pipeline()} hands the same initializer to a
 * {@link io.netty.bootstrap.ServerBootstrap} of your own, and everything below the pipeline - the transport,
 * the threads, the channel options - stays on {@link NettyServerBuilder}, which
 * {@link #start(NettyServerBuilder)} takes.
 * <p>
 * What belongs to the api - the routes, the version, the help - is the {@link RestApiBuilder}'s, and is not
 * repeated here.
 */
public final class RestServer {
    /**
     * How large an aggregated request may be. Enough for a form or a JSON body; a server which uploads
     * needs more, and one which never reads a body wants less.
     */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;

    /**
     * The whole server in one call, with everything at its default.
     *
     * @param port to listen on, or 0 to let the OS pick one.
     * @param api to route requests with.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final int port,
                                    final RestRouter api) throws InterruptedException {
        return of(api).start(port);
    }

    /**
     * @param api to route requests with, from {@link RestApiBuilder#build()}.
     * @return a server to configure and then start.
     */
    public static RestServer of(final RestRouter api) {
        return new RestServer(api);
    }

    private final RestRouter api;
    private final List<Supplier<ChannelHandler>> handlers = new ArrayList<>();

    private HttpErrorHandler errorHandler = new JsonErrorHandler();
    private ChannelErrorHandler channelErrorHandler = new StdErrChannelErrorHandler();
    private ResponseChunks responseChunks = ResponseChunks.defaults();
    private HttpApiObserverFactory observers;
    private FileSet files;
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    private boolean compression;

    private RestServer(final RestRouter api) {
        this.api = api;
    }

    /**
     * Compresses what the api answers. Off by default: it costs CPU per response, and a payload which is
     * already compressed gains nothing.
     *
     * <p>The compressor is placed behind the file handler, where it never sees a file - so files keep being
     * sent with {@code sendfile(2)} while api responses still compress. A compressor in front of the file
     * handler would quietly cost that; assemble the pipeline by hand if that is what you want.
     *
     * @return this builder.
     */
    public RestServer withCompression() {
        this.compression = true;
        return this;
    }

    /**
     * Serves a set of files in front of the api, which then answers only what the files do not own.
     *
     * @param files to serve, from {@link FileSet#builder()}.
     * @return this builder.
     */
    public RestServer withFiles(final FileSet files) {
        this.files = files;
        return this;
    }

    /**
     * @param errorHandler rendering error responses, a {@link JsonErrorHandler} by default.
     * @return this builder.
     */
    public RestServer withErrorHandler(final HttpErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    /**
     * @param channelErrorHandler told about channel failures, null to say nothing.
     * @return this builder.
     */
    public RestServer withChannelErrorHandler(final ChannelErrorHandler channelErrorHandler) {
        this.channelErrorHandler = channelErrorHandler;
        return this;
    }

    /**
     * @param responseChunks policy and accounting shared by every channel of this server.
     * @return this builder.
     */
    public RestServer withResponseChunks(final ResponseChunks responseChunks) {
        this.responseChunks = responseChunks;
        return this;
    }

    /**
     * Sets what is asked for an observer per request. Nothing is observed without it. The same factory
     * reaches the files as well as the api, so one request is observed once wherever it is answered.
     *
     * @param observers asked for an observer per request, null to observe nothing. A
     *                  {@link RestApiObserverFactory} additionally gets the stages after routing.
     * @return this builder.
     */
    public RestServer withObservers(final HttpApiObserverFactory observers) {
        this.observers = observers;
        return this;
    }

    /**
     * @param bytes an aggregated request may be, {@link #DEFAULT_MAX_CONTENT_LENGTH} by default.
     * @return this builder.
     */
    public RestServer withMaxContentLength(final int bytes) {
        this.maxContentLength = bytes;
        return this;
    }

    /**
     * Adds a handler of your own, in the order added.
     *
     * <p>It goes <i>in front of</i> the api handler, because that is the only place from which a handler
     * can still act: {@link RestApiHandler} answers every request it sees, with a 404 when nothing routed,
     * so nothing behind it would ever run. This is where a filter goes - authentication, rate limiting - or
     * a {@code WsApiHandler}, which passes on a request that is not its handshake.
     *
     * @param handler asked for one handler per channel, because a handler is rarely {@code @Sharable}.
     * @return this builder.
     */
    public RestServer withHandler(final Supplier<ChannelHandler> handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * The pipeline of one channel, to hand to a {@link io.netty.bootstrap.ServerBootstrap} assembled by
     * hand. A fresh set of handlers is built per channel - none of them is {@code @Sharable}.
     *
     * @return the initializer of every accepted channel.
     */
    public ChannelInitializer<Channel> pipeline() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel ch) {
                initPipeline(ch.pipeline());
            }
        };
    }

    /**
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final int port) throws InterruptedException {
        return start(new NettyServerBuilder().port(port));
    }

    /**
     * Starts on a bootstrap of your own - the way to reach the transport, the thread counts, the water
     * marks or any channel option without giving up the pipeline this class assembles.
     *
     * @param bootstrap to start on; its child handler is set here.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public NettyServer start(final NettyServerBuilder bootstrap) throws InterruptedException {
        return bootstrap.childHandler(pipeline()).start();
    }

    private void initPipeline(final ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxContentLength, true));

        // no ChunkedWriteHandler: the first chunked response puts one in front of the api handler itself,
        // and only on the channels which need it

        if (files != null) {
            pipeline.addLast(new FileServerHandler(files, errorHandler, channelErrorHandler, observers));
        }

        for (int i = 0; i < handlers.size(); i++) {
            pipeline.addLast(handlers.get(i).get());
        }

        if (compression) {
            pipeline.addLast(new HttpContentCompressor());
        }

        pipeline.addLast(
                new RestApiHandler(
                        api,
                        errorHandler,
                        channelErrorHandler,
                        responseChunks,
                        observers
                )
        );
    }
}
