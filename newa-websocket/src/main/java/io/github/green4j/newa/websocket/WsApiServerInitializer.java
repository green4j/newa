package io.github.green4j.newa.websocket;

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
    private final int maxRequestContentLength;
    private final ClientSessions sessionManager;
    private final SendingResult sendingResult;
    private final Receiver receiver;

    WsApiServerInitializer(final String websocketPath,
                           final SslContext sslCtx,
                           final int maxRequestContentLength,
                           final ClientSessions sessionManager,
                           final SendingResult sendingResult,
                           final Receiver receiver) {
        this.websocketPath = websocketPath;
        this.sslCtx = sslCtx;
        this.maxRequestContentLength = maxRequestContentLength;
        this.sessionManager = sessionManager;
        this.sendingResult = sendingResult;
        this.receiver = receiver;
    }

    public String websocketPath() {
        return websocketPath;
    }

    @Override
    public void initChannel(final SocketChannel ch) throws Exception {
        final ChannelPipeline pipeline = ch.pipeline();
        if (sslCtx != null) {
            pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        }

        final WsProtocolHandler wsProtocolHandler =
                new WsProtocolHandler(websocketPath, sessionManager, sendingResult, receiver);

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxRequestContentLength));
        pipeline.addLast(new WebSocketServerCompressionHandler());
        pipeline.addLast(wsProtocolHandler);
        pipeline.addLast(new WsFrameHandler(wsProtocolHandler));
    }
}
