package io.github.green4j.newa.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

class WsProtocolHandler extends WebSocketServerProtocolHandler implements ClientSessionHolder {
    private final ClientSessions sessionManager;
    private final SendingResult sendingResult;
    private final Receiver receiver;

    private ClientSession session;

    WsProtocolHandler(final String websocketPath,
                             final ClientSessions sessionManager,
                             final SendingResult sendingResult,
                             final Receiver receiver) {
        super(websocketPath, null, true);

        this.sessionManager = sessionManager;
        this.sendingResult = sendingResult;
        this.receiver = receiver;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            session = sessionManager.newClientSession(
                    new ClientSessionContext(sendingResult, receiver, ctx.channel()));
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (session != null) {
            session.close();
        }
        super.channelInactive(ctx);
    }

    @Override
    public ClientSession session() {
        return session;
    }
}
