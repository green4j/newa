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
import io.github.green4j.newa.server.DecoderFailureHandler;
import io.github.green4j.newa.server.IdleConnectionHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.RequestDeadlineHandler;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Everything an HTTP server of this module is before it answers anything: the connection, the codec, the
 * cross-origin protocol and the handlers around them. {@link RestServer} and
 * {@link io.github.green4j.newa.rest.files.FileServer} are the two, and they differ only in what stands at
 * the end:
 * <pre>
 * Client --&gt; [IdleConnectionHandler] --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt;
 *            [RequestDeadlineHandler] --&gt; [ResponseDeadlineHandler] --&gt; DecoderFailureHandler --&gt;
 *            [CorsHandler] --&gt; ... whatever the server answers with
 * </pre>
 * That head is assembled here so that the two cannot drift apart in it, and the tail is
 * {@link #initTail(ChannelPipeline)} - the one method a server of this kind has to write, and the one place
 * the difference between them lives.
 * <p>
 * The type parameter is what keeps a chain of {@code with...} calls typed as the server it started on, the
 * way Netty's own {@code AbstractBootstrap} does it. Nothing else needs it.
 * <p>
 * Two of these settings land in a different place on each server - {@link #withCompression()} and
 * {@link #withHandler(Supplier)} - because where they land is what that server is. One name and one
 * signature for each all the same; each server draws its own pipeline and says which place it means.
 *
 * @param <S> the server this returns from every {@code with...}.
 */
public abstract class AbstractHttpServer<S extends AbstractHttpServer<S>> {
    /**
     * How large the <b>body</b> of a request may be. Enough for a form or a JSON body; a server which uploads
     * needs more, and one which never reads a body wants less.
     *
     * <p>The body and nothing else: the request line and the headers are bounded by the codec instead -
     * {@link #withMaxInitialLineLength(int)} and {@link #withMaxHeaderSize(int)} - and nothing here bounds a
     * response, as what this server writes never passes through the aggregator. A declared
     * {@code Content-Length} past this is refused before the body is read at all, and a body which grows past
     * it as it arrives is refused when it does; either way the answer is {@code 413} and the connection is
     * closed.
     */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;

    /**
     * How long the request line - the method, the whole uri and the version - may be, unless
     * {@link #withMaxInitialLineLength(int)} says otherwise. Netty's own default, and the number a long uri
     * meets first: a query string carrying a signature or a base64 parameter is what usually reaches it.
     */
    public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH;

    /**
     * How large the header block of a request may be, all of it together, unless
     * {@link #withMaxHeaderSize(int)} says otherwise. Netty's own default. A bearer token and a browser's
     * cookies are what usually reach it.
     */
    public static final int DEFAULT_MAX_HEADER_SIZE = HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE;

    /**
     * How long a request has to arrive, and how long a unit of a response has to reach the peer, unless
     * {@link #withRequestDeadlineMs(int)} or {@link #withResponseDeadlineMs(int)} says otherwise. One number
     * in both directions, half the idle timeout: a connection which is busy has that long to be worth
     * holding, and one which is not belongs to the idle timeout instead.
     */
    public static final int DEFAULT_DEADLINE_MS = 30_000;

    /**
     * How long a connection may read and write nothing before it is closed, unless
     * {@link #withIdleTimeoutMs(int)} says otherwise. On by default: a keep-alive connection whose client
     * walked away, and a peer which died without a FIN, cost a file descriptor each until something takes
     * it back, and nothing else here would.
     */
    public static final int DEFAULT_IDLE_TIMEOUT_MS = DEFAULT_DEADLINE_MS * 2;

    private final List<Supplier<ChannelHandler>> handlers = new ArrayList<>();

    private HttpErrorHandler errorHandler = new JsonErrorHandler();
    private ChannelErrorHandler channelErrorHandler = new StdErrChannelErrorHandler();
    private HttpApiObserverFactory observers;
    private CorsConfig cors;
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    private int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
    private int maxHeaderSize = DEFAULT_MAX_HEADER_SIZE;
    private int idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private int requestDeadlineMs = DEFAULT_DEADLINE_MS;
    private int responseDeadlineMs = DEFAULT_DEADLINE_MS;
    private boolean compression;

    /**
     * Compresses what this server answers with. Off by default: it costs CPU per response, and a payload
     * which is already compressed gains nothing.
     *
     * <p>Where the compressor goes is the server's own business, and it is not a detail: a file written
     * past one is a file which had to be read into the process first. Each server says where it puts it.
     *
     * @return this builder.
     */
    public S withCompression() {
        this.compression = true;
        return self();
    }

    /**
     * Sets how long a connection may read and write nothing before it is closed. What it takes back is a
     * file descriptor: a connection which opened and never asked anything, a keep-alive connection whose
     * client walked away, and a peer which died without a FIN - the last being the one no amount of correct
     * client code prevents.
     *
     * <p>Both directions count, which is what makes it safe in front of a long response: a chunked response
     * still being written keeps its own connection alive however long it takes, and only a connection where
     * <b>neither</b> side has said anything is closed. A transfer counts while it is moving rather than
     * when it lands, so one large file to one slow peer - a single write which completes at the end - is
     * not cut off in the middle of itself. A response which suspends for longer than this while writing
     * nothing is the case to raise it for.
     *
     * <p><b>It judges neither of the two slow peers</b>, and cannot: all it knows is that bytes moved. A
     * client dribbling a header block a byte at a time is reading and writing all the while, and a peer
     * taking a response a byte every ten seconds moves the outbound buffer every ten seconds - both look
     * busy from here. They are bounded by the pair which counts what actually arrived,
     * {@link #withRequestDeadlineMs(int)} and {@link #withResponseDeadlineMs(int)}, both half this default
     * and both therefore what decides. Keep this above them: set below, it takes their decisions over and
     * takes them on a worse measurement.
     *
     * <p>What is left for this one is the connection nobody is using at all - which is the common case, and
     * the one nothing else here would ever close.
     *
     * @param idleTimeoutMs of silence in both directions, {@link #DEFAULT_IDLE_TIMEOUT_MS} by default, 0 to
     *                      hold a connection which says nothing for as long as the peer likes.
     * @return this builder.
     */
    public S withIdleTimeoutMs(final int idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
        return self();
    }

    /**
     * Sets how long a request has to arrive once it has begun arriving - the bound an idle timeout cannot be.
     * A client sending a header block a byte at a time is never idle, so what has to be bounded is the time
     * the request itself may take, and nothing the peer sends extends it once it is running. nginx calls this
     * {@code client_header_timeout}, Tomcat {@code connectionTimeout}, Node {@code headersTimeout}.
     *
     * <p>Every request of a keep-alive connection is judged, not only the first, and so is a connection which
     * opens and asks nothing. Between requests nothing is running: a quiet keep-alive connection belongs to
     * {@link #withIdleTimeoutMs(int)}, and a response being written to
     * {@link #withResponseDeadlineMs(int)}.
     *
     * <p>It covers the request whole, body and all, which is what {@link #withMaxContentLength(int)} makes
     * honest: a server which raises that to take uploads raises this with it.
     *
     * @param requestDeadlineMs a request has to arrive within, {@link #DEFAULT_DEADLINE_MS} by default, 0 to
     *                          let one arrive as slowly as the peer likes.
     * @return this builder.
     */
    public S withRequestDeadlineMs(final int requestDeadlineMs) {
        this.requestDeadlineMs = requestDeadlineMs;
        return self();
    }

    /**
     * Sets how long one unit of a response - 64K, the size everything here is written in - has to reach the
     * peer. This is what judges a slow reader, and it judges on what actually arrived rather than on whether
     * anything moved at all, which is why it, and not the idle timeout, is what decides.
     *
     * <p><b>Nothing is timed while nothing is owed.</b> The clock starts on a write and stops once every
     * write has landed, so a response which is merely slow to produce - a chunked one ticking once a minute,
     * a suspended cursor - is never on it. What is timed is a peer which has been given something and is not
     * taking it.
     *
     * <p>Each write is given one window for every unit of it, so a large response is not judged by the clock
     * of a small one, and a file renews its window every time another unit of it has reached the peer - which
     * is how a trickle is caught inside a transfer of any size.
     *
     * @param responseDeadlineMs a unit of a response has to reach the peer within,
     *                           {@link #DEFAULT_DEADLINE_MS} by default, 0 to wait for a peer as long as it
     *                           likes.
     * @return this builder.
     */
    public S withResponseDeadlineMs(final int responseDeadlineMs) {
        this.responseDeadlineMs = responseDeadlineMs;
        return self();
    }

    /**
     * Answers the browser's cross-origin protocol - the preflight and the {@code Access-Control-} headers -
     * with Netty's own {@link CorsHandler}. Nothing is answered without one, which is right for a server a
     * browser never calls directly and wrong for one it does.
     * <pre>{@code
     * RestServer.of(api)
     *           .withCors(CorsConfigBuilder.forOrigin("https://app.example.com")
     *                                      .allowedRequestMethods(HttpMethod.GET, HttpMethod.POST)
     *                                      .shortCircuit()   // answer a wrong origin 403 rather than
     *                                      .build())         // let it through without the headers
     *           .start(9010);
     * }</pre>
     * It goes in front of everything which answers, which is what makes a file carry the headers too: a
     * {@code FileServerHandler} writes its response head from its own place in the pipeline, so only a
     * handler nearer the front than it ever sees one. Note the consequence - a preflight {@code OPTIONS} is
     * answered here and never reaches the files or the api, so the {@code 405} a file path gives an
     * {@code OPTIONS} is no longer what a browser sees.
     *
     * <p>A websocket handshake is not covered by any of this and does not need to be: there is no preflight
     * on one and no header to add to its response. It gets an
     * {@code io.github.green4j.newa.websocket.OriginPolicy} instead, which answers yes or no - and which,
     * unlike this, is on by default there.
     *
     * @param cors to answer with, from {@link io.netty.handler.codec.http.cors.CorsConfigBuilder}, null to
     *             answer nothing.
     * @return this builder.
     */
    public S withCors(final CorsConfig cors) {
        this.cors = cors;
        return self();
    }

    /**
     * @param errorHandler rendering error responses, a {@link JsonErrorHandler} by default.
     * @return this builder.
     */
    public S withErrorHandler(final HttpErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        return self();
    }

    /**
     * @param channelErrorHandler told about channel failures, null to say nothing.
     * @return this builder.
     */
    public S withChannelErrorHandler(final ChannelErrorHandler channelErrorHandler) {
        this.channelErrorHandler = channelErrorHandler;
        return self();
    }

    /**
     * Sets what is asked for an observer per request. Nothing is observed without it.
     *
     * @param observers asked for an observer per request, null to observe nothing. A
     *                  {@link RestApiObserverFactory} additionally gets the stages after routing, which
     *                  only a {@link RestApiHandler} has to report.
     * @return this builder.
     */
    public S withObservers(final HttpApiObserverFactory observers) {
        this.observers = observers;
        return self();
    }

    /**
     * @param bytes the body of a request may be, {@link #DEFAULT_MAX_CONTENT_LENGTH} by default. Headers are
     *              not counted in it, and a response is not bounded by it at all.
     * @return this builder.
     */
    public S withMaxContentLength(final int bytes) {
        this.maxContentLength = bytes;
        return self();
    }

    /**
     * Sets how long the request line may be - the method, the whole uri, the version. A uri past it is
     * answered {@code 414} and the connection closed, because a decoder which has refused a request line
     * never reads that connection again.
     *
     * @param bytes the request line may be, {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH} by default.
     * @return this builder.
     */
    public S withMaxInitialLineLength(final int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException(
                    "A request line no byte fits in would refuse every request: " + bytes);
        }
        this.maxInitialLineLength = bytes;
        return self();
    }

    /**
     * Sets how large the header block of a request may be, all of its headers together. A block past it is
     * answered {@code 431} and the connection closed, for the same reason.
     *
     * @param bytes the headers of a request may be, {@link #DEFAULT_MAX_HEADER_SIZE} by default.
     * @return this builder.
     */
    public S withMaxHeaderSize(final int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException(
                    "A header block no byte fits in would refuse every request: " + bytes);
        }
        this.maxHeaderSize = bytes;
        return self();
    }

    /**
     * Adds a handler of your own, in the order added.
     *
     * <p>Where they land is the server's own business and is how one port comes to serve two things - a
     * {@code FileServerHandler} in front of a REST api, a {@code RestApiHandler} behind a file server. Each
     * server says which place it means.
     *
     * @param handler asked for one handler per channel, because a handler is rarely {@code @Sharable}.
     * @return this builder.
     */
    public S withHandler(final Supplier<ChannelHandler> handler) {
        handlers.add(handler);
        return self();
    }

    /**
     * The pipeline of one channel, to hand to a {@link io.netty.bootstrap.ServerBootstrap} assembled by
     * hand. A fresh set of handlers is built per channel - none of them is {@code @Sharable}.
     *
     * @return the initializer of every accepted channel.
     */
    public final ChannelInitializer<Channel> pipeline() {
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
    public final NettyServer start(final int port) throws InterruptedException {
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
    public final NettyServer start(final NettyServerBuilder bootstrap) throws InterruptedException {
        return bootstrap.childHandler(pipeline()).start();
    }

    /**
     * Whatever this server answers with, added behind the head every one of them shares.
     *
     * @param pipeline of one accepted channel, with the codec and the cross-origin handler already in it.
     */
    protected abstract void initTail(ChannelPipeline pipeline);

    /**
     * @return what error responses are rendered with, never null.
     */
    protected final HttpErrorHandler errorHandler() {
        return errorHandler;
    }

    /**
     * @return what channel failures are reported to, or null to report nothing.
     */
    protected final ChannelErrorHandler channelErrorHandler() {
        return channelErrorHandler;
    }

    /**
     * @return what is asked for an observer per request, or null to observe nothing.
     */
    protected final HttpApiObserverFactory observers() {
        return observers;
    }

    /**
     * @return whether what this server answers with is to be compressed.
     */
    protected final boolean compression() {
        return compression;
    }

    /**
     * Adds the handlers {@link #withHandler(Supplier)} was given, in the order they were added, wherever
     * the tail being assembled has decided they belong.
     *
     * @param pipeline of one accepted channel.
     */
    protected final void addHandlers(final ChannelPipeline pipeline) {
        for (int i = 0; i < handlers.size(); i++) {
            pipeline.addLast(handlers.get(i).get());
        }
    }

    private void initPipeline(final ChannelPipeline pipeline) {
        if (idleTimeoutMs > 0) {
            // first, in front of the codec: what it measures is traffic, not messages, and a decoder still
            // waiting for the rest of one has nothing to hand on
            pipeline.addLast(new IdleConnectionHandler(idleTimeoutMs));
        }

        pipeline.addLast(new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(maxInitialLineLength)
                .setMaxHeaderSize(maxHeaderSize)));
        pipeline.addLast(new HttpObjectAggregator(maxContentLength, true));

        if (requestDeadlineMs > 0) {
            // behind the aggregator, which is the only place this rule can be expressed at all: what it has
            // to tell apart is bytes which became a request from bytes which did not, and in front of a
            // decoder every read looks the same
            pipeline.addLast(new RequestDeadlineHandler(requestDeadlineMs));
        }

        if (responseDeadlineMs > 0) {
            // in front of everything which answers, so that every response passes through it, and behind the
            // codec, so that what it counts is the payload rather than the frame put around it
            pipeline.addLast(new ResponseDeadlineHandler(responseDeadlineMs));
        }

        // in front of everything which answers, so that nothing behind it has to ask whether the request it
        // was given is a real one: what the codec refused arrives as a substitute request, and would be
        // answered 404 by the api rather than 414 or 431 by anybody
        pipeline.addLast(new DecoderFailureHandler());

        if (cors != null) {
            // in front of everything which answers: a handler which writes its response head from its own
            // place in the pipeline - the file handler does - never sees a decoration added behind it
            pipeline.addLast(new CorsHandler(cors));
        }

        initTail(pipeline);
    }

    @SuppressWarnings("unchecked")
    private S self() {
        return (S) this;
    }
}
