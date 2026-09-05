/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentCompressor;

/**
 * A REST server in one line:
 * <pre>{@code
 * new Life().run(() -> RestServer.start(api, 9009));
 * }</pre>
 * and the same thing with something turned on:
 * <pre>{@code
 * RestServer.of(api)
 *         .withCompression()
 *         .withObservers(observers)
 *         .start(9009);
 * }</pre>
 * <p>
 * It assembles this pipeline, out of the same public handlers a pipeline written by hand is made of:
 * <pre>
 * Client --&gt; [IdleConnectionHandler] --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt;
 *            [RequestDeadlineHandler] --&gt; [ResponseDeadlineHandler] --&gt; DecoderFailureHandler --&gt;
 *            [CorsHandler] --&gt; [your handlers] --&gt; [HttpContentCompressor] --&gt; RestApiHandler
 * </pre>
 * Nothing is hidden and nothing is one-way: {@link #pipeline()} hands the same initializer to a
 * {@link io.netty.bootstrap.ServerBootstrap} of your own, and everything below the pipeline - the transport,
 * the threads, the channel options - stays on {@link NettyServerBuilder}, which
 * {@link #start(NettyServerBuilder)} takes.
 * <p>
 * What belongs to the api - the routes, the version, the help - is the {@link RestApiBuilder}'s, and is not
 * repeated here. Files are {@link io.github.green4j.newa.rest.files.FileServer}'s, and are served here the
 * way anything else is added - a {@link io.github.green4j.newa.rest.files.FileServerHandler} through
 * {@link #withHandler(java.util.function.Supplier)}, which lands it in front of the api and behind the
 * compressor.
 */
public final class RestServer extends AbstractHttpServer<RestServer> {
    /**
     * The whole server in one call, with everything at its default.
     *
     * @param api to route requests with.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public static NettyServer start(final RestRouter api,
                                    final int port) throws InterruptedException {
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

    private ResponseChunks responseChunks = ResponseChunks.defaults();

    private RestServer(final RestRouter api) {
        this.api = api;
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
     * The api handler, with everything added by hand in front of it.
     *
     * <p>{@link #withHandler(java.util.function.Supplier)} lands there because it is the only place from
     * which a handler can still act: {@link RestApiHandler} answers every request it sees, with a 404 when
     * nothing routed, so nothing behind it would ever run. This is where a filter goes - authentication,
     * rate limiting - or a {@code FileServerHandler}, or a {@code WsApiHandler}, both of which pass on a
     * request that is not theirs.
     *
     * <p>{@link #withCompression()} lands behind all of them, where it compresses what the api returns and
     * never sees a file - which is what lets a file handler added above keep {@code sendfile(2)}. A
     * compressor in front of one costs that, and reaching it means assembling the pipeline by hand.
     *
     * @param pipeline of one accepted channel.
     */
    @Override
    protected void initTail(final ChannelPipeline pipeline) {
        // no ChunkedWriteHandler: the first chunked response puts one in front of the api handler itself,
        // and only on the channels which need it

        addHandlers(pipeline);

        if (compression()) {
            pipeline.addLast(new HttpContentCompressor());
        }

        pipeline.addLast(
                new RestApiHandler(
                        api,
                        errorHandler(),
                        channelErrorHandler(),
                        responseChunks,
                        observers()
                )
        );
    }
}
