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

package io.github.green4j.newa.performance.rest.newa;

import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.Transport;
import io.github.green4j.newa.performance.rest.RestPayload;
import io.github.green4j.newa.performance.rest.RestServer;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * newa's side of the benchmark: the routing and the response are exactly what the framework's own examples
 * do, with nothing added for the occasion.
 * <p>
 * The rows are written straight into the response buffer as they are produced, which is the idiom newa
 * exists for - no intermediate objects, no document held twice. That is not the same work Jackson does on
 * the other side, and it is not meant to be: it is what each framework asks of the code that uses it.
 * <p>
 * Note what is <i>not</i> in the pipeline. There is no {@code HttpContentCompressor}, because the Spring
 * server has no compression either, and there is no observer, because a benchmark should not be measuring
 * its own instrumentation.
 */
public final class NewaRestServer implements RestServer {
    private static final String LOCAL_IFC = "127.0.0.1";

    private static final int MAX_REQUEST_BYTES = 8192;

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel channel;
    private final int port;

    private NewaRestServer(final EventLoopGroup bossGroup,
                           final EventLoopGroup workerGroup,
                           final Channel channel) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.channel = channel;
        this.port = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /**
     * @param port to listen on, or 0 for an ephemeral one
     * @param workers event loops to serve requests from - the half of the machine the load client left
     * @return the running server
     * @throws InterruptedException if the calling thread is interrupted while binding
     */
    public static NewaRestServer start(final int port,
                                       final int workers) throws InterruptedException {
        final RestApi api = buildApi();

        final EventLoopGroup bossGroup =
                new MultiThreadIoEventLoopGroup(1, Transport.ioHandlerFactory());
        final EventLoopGroup workerGroup =
                new MultiThreadIoEventLoopGroup(workers, Transport.ioHandlerFactory());

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(Transport.serverSocketChannel())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES, true));
                        ch.pipeline().addLast(
                                new RestApiHandler(
                                        api,
                                        new JsonErrorHandler(),
                                        (ch2, cause) -> {
                                            if (cause instanceof IOException) {
                                                // every run ends with the client dropping its connections,
                                                // and a peer going away is not the server's fault
                                                return;
                                            }
                                            System.err.println("Channel error: " + cause);
                                            cause.printStackTrace(System.err);
                                        }
                                )
                        );
                    }
                });

        try {
            final Channel channel = bootstrap
                    .bind(InetAddress.getByName(LOCAL_IFC), port)
                    .sync()
                    .channel();
            return new NewaRestServer(bossGroup, workerGroup, channel);
        } catch (final Exception e) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            throw new IllegalStateException("Could not bind the newa server to port " + port, e);
        }
    }

    private static RestApi buildApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                "newa Performance API",
                "The endpoint the REST benchmark measures",
                1,
                "0.0.1"
        );

        apiBuilder.getJson("/quotes/{sequence}", (context, output) ->
                render(context.pathParameters().valueRequiredAsLong("sequence"), output))
                .withPathParameterDescriptions("sequence - Which document to render");

        apiBuilder.getTxt("/perf/stats", (context, output) ->
                output.append(JvmStats.current().render()));

        return apiBuilder.build();
    }

    private static void render(final long sequence,
                               final JsonGenerator output) {
        output.startArray();
        for (int row = 0; row < RestPayload.ROWS; row++) {
            final long key = RestPayload.key(sequence, row);
            output.startObject();
            output.objectMember(RestPayload.ID);
            output.numberValue(RestPayload.id(key));
            output.objectMember(RestPayload.SYMBOL);
            output.stringValue(RestPayload.symbol(key));
            output.objectMember(RestPayload.VENUE);
            output.stringValue(RestPayload.venue(key));
            output.objectMember(RestPayload.PRICE_MINOR);
            output.numberValue(RestPayload.priceMinor(key));
            output.objectMember(RestPayload.QUANTITY);
            output.numberValue(RestPayload.quantity(key));
            output.objectMember(RestPayload.TIMESTAMP_MILLIS);
            output.numberValue(RestPayload.timestampMillis(key));
            output.objectMember(RestPayload.FIRM);
            if (RestPayload.firm(key)) {
                output.trueValue();
            } else {
                output.falseValue();
            }
            output.objectMember(RestPayload.STATUS);
            output.stringValue(RestPayload.status(key));
            output.endObject();
        }
        output.endArray();
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public void close() {
        channel.close().awaitUninterruptibly();
        bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }
}
