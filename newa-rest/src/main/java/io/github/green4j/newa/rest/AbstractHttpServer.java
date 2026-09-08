/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.StdErrChannelErrorHandler;
import io.github.green4j.newa.server.ConnectionObserver;
import io.github.green4j.newa.server.DecoderFailureHandler;
import io.github.green4j.newa.server.IdleConnectionHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.ObservedHttpObjectAggregator;
import io.github.green4j.newa.server.RefusedRequestObserver;
import io.github.green4j.newa.server.RequestDeadlineHandler;
import io.github.green4j.newa.server.ResponseDeadlineHandler;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.server.ServerMemoryEstimate;
import io.github.green4j.newa.server.SingleHttpExchangeHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpDecoderConfig;
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
 * Client --&gt; [IdleConnectionHandler] --&gt; HttpServerCodec --&gt; ObservedHttpObjectAggregator --&gt;
 *            SingleHttpExchangeHandler --&gt; [RequestDeadlineHandler] --&gt; [ResponseDeadlineHandler] --&gt;
 *            DecoderFailureHandler --&gt; [CorsHandler] --&gt; ... whatever the server answers with
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
     * How large the <b>body</b> of a request may be on a server which does not say otherwise - a file
     * server, whose requests are {@code GET}s with nothing in them. A REST api is answered by
     * {@link RestServer#DEFAULT_MAX_CONTENT_LENGTH}, which is where a body is expected.
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
    private HttpObserverFactory observers;
    private RefusedRequestObserver refusedRequests;
    private ConnectionObserver connectionObserver;
    private CorsConfig cors;
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
    private int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
    private int maxHeaderSize = DEFAULT_MAX_HEADER_SIZE;
    private int idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private int requestDeadlineMs = DEFAULT_DEADLINE_MS;
    private int responseDeadlineMs = DEFAULT_DEADLINE_MS;
    private boolean compression;
    private ServerMemoryBudget memoryBudget;
    private long additionalHeapBytesPerConnection;
    private long additionalDirectMemoryBytesPerConnection;

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
     * Sets how long a connection may read and write nothing before {@link IdleConnectionHandler} closes it.
     * What it takes back is a file descriptor from a connection nobody is using - one which opened and never
     * asked anything, one whose client walked away, a peer which died without a FIN. Both directions count,
     * so a response still being written keeps its own connection alive however long it takes, and a response
     * which suspends for longer than this while writing nothing is the case to raise it for.
     *
     * <p><b>It judges neither of the two slow peers</b>, and cannot: all it knows is that bytes moved. Those
     * are {@link #withRequestDeadlineMs(int)} and {@link #withResponseDeadlineMs(int)}, both half this
     * default and both therefore what decides. Keep this above them.
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
     * Sets how long a request has to arrive once it has begun arriving - the bound an idle timeout cannot be,
     * since a client sending a header block a byte at a time is never idle. Nothing the peer sends extends
     * it, every request of a keep-alive connection is judged rather than only the first, and between
     * requests nothing is running. See {@link RequestDeadlineHandler}.
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
     * peer. This is what judges a slow reader, on what actually arrived rather than on whether anything
     * moved, which is why it and not the idle timeout is what decides.
     *
     * <p><b>Nothing is timed while nothing is owed</b>: the clock starts on a write and stops once every
     * write has landed, so a response which is merely slow to produce - a chunked one ticking once a minute,
     * a suspended cursor - is never on it. Each write is given one window per unit of it, and a file renews
     * its window every unit which reaches the peer. See {@link ResponseDeadlineHandler}.
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
     * with Netty's own {@link CorsHandler}. Without one a cross-origin request is still served, and a simple
     * {@code GET} or {@code POST} has had its effect by the time the response is written; what is missing is
     * the {@code Access-Control-} headers on it, so the browser refuses to let the page read the answer, and
     * a request which needs a preflight is not sent at all. That is right for a server a browser never calls
     * directly and wrong for one it does.
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
    public S withObservers(final HttpObserverFactory observers) {
        this.observers = observers;
        this.refusedRequests = observers == null ? null : new RefusedRequestReporter(observers);
        return self();
    }

    /**
     * Sets where the connections this server closes by a rule of its own are reported - an idle timeout, a
     * request or response deadline, a connection limit, a client pipelining too deep. None of them belongs
     * to a request, so none reaches {@link #withObservers(HttpObserverFactory)}, and all are silent on the
     * wire. Handed to the bootstrap's connection limit too, so one observer covers the whole server.
     *
     * @param observer told about them, null to say nothing. One serves the whole server and must not block.
     * @return this builder.
     */
    public S withConnectionObserver(final ConnectionObserver observer) {
        this.connectionObserver = observer;
        return self();
    }

    /**
     * Adds application-owned state which the built-in estimate cannot see: observer fields, custom handler
     * state, buffers retained by an application, or work mounted behind this server.
     *
     * @param heapBytesPerConnection additional estimated heap bytes per connection
     * @param directMemoryBytesPerConnection additional estimated direct-memory bytes per connection
     * @return this builder
     */
    public S withAdditionalMemoryEstimate(final long heapBytesPerConnection,
                                          final long directMemoryBytesPerConnection) {
        if (heapBytesPerConnection < 0) {
            throw new IllegalArgumentException(
                    "heapBytesPerConnection must not be negative: " + heapBytesPerConnection);
        }
        if (directMemoryBytesPerConnection < 0) {
            throw new IllegalArgumentException(
                    "directMemoryBytesPerConnection must not be negative: "
                            + directMemoryBytesPerConnection);
        }
        this.additionalHeapBytesPerConnection = heapBytesPerConnection;
        this.additionalDirectMemoryBytesPerConnection = directMemoryBytesPerConnection;
        return self();
    }

    /**
     * Sets how large the body of a request may be - and, since this server aggregates, the largest buffer
     * it holds at once. A body past it is answered {@code 413} and the connection closed.
     *
     * <p>Nothing inflates a request body: {@link #withCompression()} is outbound only. A decompressor added
     * through {@link #withHandler(Supplier)} lands behind the aggregator, so this would then bound the
     * compressed body alone and the inflated one needs a maximum allocation of its own.
     *
     * @param bytes the body of a request may be, {@link #DEFAULT_MAX_CONTENT_LENGTH} by default and
     *              {@link RestServer#DEFAULT_MAX_CONTENT_LENGTH} on a REST server. Headers are not counted
     *              in it, and a response is not bounded by it at all.
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
     * Binds the <b>loopback</b>, which is where {@link NettyServerBuilder#DEFAULT_HOST} leaves a server
     * nobody opened up. {@link #start(String, int)} is the one which is reachable from anywhere else.
     *
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public final NettyServer start(final int port) throws InterruptedException {
        return start(new NettyServerBuilder().port(port));
    }

    /**
     * The same on an interface of your own: the address of the network this server belongs on, or
     * {@link NettyServerBuilder#ANY_HOST} for every interface. Naming one is how a server becomes reachable
     * at all - the default is the loopback.
     *
     * @param host to bind, or {@link NettyServerBuilder#ANY_HOST} for every interface.
     * @param port to listen on, or 0 to let the OS pick one.
     * @return the running server, on a bootstrap left at its defaults.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public final NettyServer start(final String host,
                                   final int port) throws InterruptedException {
        return start(new NettyServerBuilder().host(host).port(port));
    }

    /**
     * Where the two builders meet: this one is everything above the socket, the bootstrap is the socket -
     * the transport, the thread counts, the water marks, the channel options - and this hands the pipeline
     * assembled here to it and binds.
     *
     * @param bootstrap to start on; its child handler is set here, so anything it already had is replaced.
     * @return the running server.
     * @throws InterruptedException if the calling thread is interrupted while binding.
     */
    public final NettyServer start(final NettyServerBuilder bootstrap) throws InterruptedException {
        if (memoryBudget != null) {
            bootstrap.memoryBudget(memoryBudgetName(), memoryBudget, memoryEstimate(bootstrap));
        }
        if (connectionObserver != null) {
            // the connection limit lives on the bootstrap, in front of the pipeline assembled here
            bootstrap.connectionObserver(connectionObserver);
        }
        return bootstrap.childHandler(pipeline()).start();
    }

    /**
     * @return the name this kind of server uses in memory-budget statistics
     */
    protected abstract String memoryBudgetName();

    /**
     * @param bootstrap carrying the final transport settings
     * @return the estimated memory reserved by one connection
     */
    protected abstract ServerMemoryEstimate memoryEstimate(NettyServerBuilder bootstrap);

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
    protected final HttpObserverFactory observers() {
        return observers;
    }

    /**
     * @return whether what this server answers with is to be compressed.
     */
    protected final boolean compression() {
        return compression;
    }

    protected final int maxContentLength() {
        return maxContentLength;
    }

    protected final int maxInitialLineLength() {
        return maxInitialLineLength;
    }

    protected final int maxHeaderSize() {
        return maxHeaderSize;
    }

    protected final long additionalHeapBytesPerConnection() {
        return additionalHeapBytesPerConnection;
    }

    protected final long additionalDirectMemoryBytesPerConnection() {
        return additionalDirectMemoryBytesPerConnection;
    }

    protected final void setMemoryBudget(final ServerMemoryBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("A server memory budget is required");
        }
        this.memoryBudget = budget;
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
            pipeline.addLast(new IdleConnectionHandler(idleTimeoutMs, connectionObserver));
        }

        pipeline.addLast(new HttpServerCodec(new HttpDecoderConfig()
                .setMaxInitialLineLength(maxInitialLineLength)
                .setMaxHeaderSize(maxHeaderSize)));
        // Netty's aggregator answers an oversized body from here, in front of everything which would have
        // reported it - which is why it is this one, which says so
        pipeline.addLast(new ObservedHttpObjectAggregator(maxContentLength, true, refusedRequests));
        // directly behind the aggregator, so that what it counts is whole requests and whole responses:
        // it holds this connection to one unfinished response, replaying a request the codec had already
        // decoded once that response is written
        pipeline.addLast(new SingleHttpExchangeHandler(connectionObserver));

        if (requestDeadlineMs > 0) {
            // behind the aggregator, which is the only place this rule can be expressed at all: what it has
            // to tell apart is bytes which became a request from bytes which did not, and in front of a
            // decoder every read looks the same
            pipeline.addLast(new RequestDeadlineHandler(requestDeadlineMs, connectionObserver));
        }

        if (responseDeadlineMs > 0) {
            // in front of everything which answers, so that every response passes through it, and behind the
            // codec, so that what it counts is the payload rather than the frame put around it
            pipeline.addLast(new ResponseDeadlineHandler(
                    responseDeadlineMs,
                    ResponseDeadlineHandler.DEFAULT_UNIT,
                    connectionObserver
            ));
        }

        // in front of everything which answers, so that nothing behind it has to ask whether the request it
        // was given is a real one: what the codec refused arrives as a substitute request, and would be
        // answered 404 by the api rather than 414 or 431 by anybody
        pipeline.addLast(new DecoderFailureHandler(refusedRequests));

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
