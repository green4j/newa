package io.github.green4j.newa.rest;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.ssl.SslContext;

public class RestApiServerInitializer extends ChannelInitializer<SocketChannel> {
    private final SslContext sslCtx;
    private final HttpHandler apiHandler;
    private final int maxRequestContentLength;
    private final CorsConfig corsConfig;

    public RestApiServerInitializer(final SslContext sslCtx,
                                    final HttpHandler apiHandler,
                                    final int maxRequestContentLength,
                                    final CorsConfig corsConfig) {
        this.sslCtx = sslCtx;
        this.apiHandler = apiHandler;
        this.maxRequestContentLength = maxRequestContentLength;
        this.corsConfig = corsConfig;
    }

    @Override
    public void initChannel(final SocketChannel ch) {
        final ChannelPipeline pipeline = ch.pipeline();
        if (sslCtx != null) {
            pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        }

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpServerExpectContinueHandler());
        pipeline.addLast(new HttpObjectAggregator(maxRequestContentLength, true));
        if (corsConfig != null) {
            pipeline.addLast(new CorsHandler(corsConfig));
        }
        pipeline.addLast(new RestApiProtocolHandler(apiHandler));
    }
}
