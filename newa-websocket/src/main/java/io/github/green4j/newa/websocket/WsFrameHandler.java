package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

class WsFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final ClientSessionHolder sessionHolder;
    private final ChannelErrorHandler channelErrorHandler;

    WsFrameHandler(final ClientSessionHolder sessionHolder,
                   final ChannelErrorHandler channelErrorHandler) {
        this.sessionHolder = sessionHolder;
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            final String request = ((TextWebSocketFrame) frame).text();
            sessionHolder.session().receive(request);
            return;
        }

        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        try {
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(ctx.channel(), cause);
            }
        } finally {
            ctx.close();
        }
    }
}
