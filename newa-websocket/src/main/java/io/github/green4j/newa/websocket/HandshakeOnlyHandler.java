/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpRequest;

/**
 * The end of the pipeline: an HTTP request which was neither the handshake nor anything a handler of yours
 * took is reported once and the connection is closed.
 * <p>
 * This port speaks HTTP exactly once - to be upgraded away from it - so there is no response for such a
 * request to be given, and none is invented here. What there is instead is the reason the connection went:
 * a {@link NotAHandshakeException} to the {@link ChannelErrorHandler}, carrying the method and the uri, and
 * typed so that a handler can tell a wrongly aimed health check from a server which broke.
 * <p>
 * Closing is not tidiness. Netty's handshake handler passes on a uri it does not recognise, and with
 * nothing behind it that request reaches the end of the pipeline, where it is discarded in silence while
 * <b>the connection stays open for as long as the peer keeps it</b>. Nothing before the handshake is on a
 * timer either - the ping interval and the read timeout belong to a session, which does not exist yet - so
 * a scanner would hold a socket for good.
 * <p>
 * {@link WsServer} puts one at the end of every pipeline it assembles, behind the handlers added with
 * {@link WsServer#withHandler}: a {@code RestApiHandler} there still answers first, and what reaches here
 * is only what nothing else wanted. A pipeline written by hand wants one too, last.
 */
public class HandshakeOnlyHandler extends SimpleChannelInboundHandler<HttpRequest> {
    private final ChannelErrorHandler channelErrorHandler;

    /**
     * @param channelErrorHandler told which request closed the connection, or null to say nothing.
     */
    public HandshakeOnlyHandler(final ChannelErrorHandler channelErrorHandler) {
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,
                                final HttpRequest request) {
        try {
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(
                        ctx.channel(),
                        new NotAHandshakeException(request.method().name(), request.uri())
                );
            }
        } finally {
            // the report is the handler's business and the connection is ours: one which throws does not
            // get to leave the socket open
            ctx.close();
        }
    }
}
