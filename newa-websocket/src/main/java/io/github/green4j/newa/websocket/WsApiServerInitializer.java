package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;

class WsApiServerInitializer extends ChannelInitializer<SocketChannel> {
    private final String websocketPath;
    private final SslContext sslCtx;
    private final boolean withCompression;
    private final int maxRequestContentLength;
    private final ClientSessions sessionManager;
    private final SendingResult sendingResult;
    private final Receiver receiver;
    private final ChannelErrorHandler channelErrorHandler;

    WsApiServerInitializer(final String websocketPath,
                           final SslContext sslCtx,
                           final boolean withCompression,
                           final int maxRequestContentLength,
                           final ClientSessions sessionManager,
                           final SendingResult sendingResult,
                           final Receiver receiver,
                           final ChannelErrorHandler channelErrorHandler) {
        this.websocketPath = websocketPath;
        this.sslCtx = sslCtx;
        this.withCompression = withCompression;
        this.maxRequestContentLength = maxRequestContentLength;
        this.sessionManager = sessionManager;
        this.sendingResult = sendingResult;
        this.receiver = receiver;
        this.channelErrorHandler = channelErrorHandler;
    }

    public String websocketPath() {
        return websocketPath;
    }

    @Override
    public void initChannel(final SocketChannel ch) {
        final ChannelPipeline pipeline = ch.pipeline();
        if (sslCtx != null) {
            pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        }

        final WsProtocolHandler wsProtocolHandler =
                new WsProtocolHandler(websocketPath, sessionManager, sendingResult, receiver);

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxRequestContentLength));
        if (withCompression) {
            pipeline.addLast(new WebSocketServerCompressionHandler(0));
        }
        pipeline.addLast(wsProtocolHandler);
        pipeline.addLast(new WsFrameHandler(wsProtocolHandler, channelErrorHandler));
    }
}
