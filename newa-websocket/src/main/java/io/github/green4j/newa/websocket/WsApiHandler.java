package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.CloseHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.List;

public class WsApiHandler extends WebSocketServerProtocolHandler {
    private final ClientSessionFactory sessionFactory;
    private final WritingResult writingResult;
    private final Receiver receiver;
    private final long pingIntervalMs;
    private final ChannelErrorHandler channelErrorHandler;

    private ClientSession session;

    public WsApiHandler(final WsApi wsApi,
                        final ChannelErrorHandler channelErrorHandler) {
        this(
                wsApi,
                null,
                channelErrorHandler
        );
    }

    public WsApiHandler(final WsApi wsApi,
                        final Receiver receiver,
                        final ChannelErrorHandler channelErrorHandler) {
        super(wsApi.websocketPath(), null, true);

        sessionFactory = wsApi;
        writingResult = wsApi;

        this.receiver = receiver;
        this.pingIntervalMs = wsApi.pingIntervalMs();
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx,
                                   final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            session = sessionFactory.newSession(
                    new ClientSessionContext(
                            writingResult,
                            receiver,
                            ctx.channel(),
                            pingIntervalMs
                    )
            );
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx,
                          final WebSocketFrame frame,
                          final List<Object> out) throws Exception {
        if (session == null) {
            throw new IllegalStateException("Session is null");
        }

        if (frame instanceof TextWebSocketFrame) {
            final String request = ((TextWebSocketFrame) frame).text();
            session.receive(request);
            // don't add to out - we consumed it
            return;
        }

        // to handle other frames (Ping, Pong, Close, etc.) as usual
        super.decode(ctx, frame, out);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (session != null) {
            CloseHelper.closeQuiet(session);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx,
                                final Throwable cause) {
        try {
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(ctx.channel(), cause);
            }
        } finally {
            ctx.close();
        }
    }
}
